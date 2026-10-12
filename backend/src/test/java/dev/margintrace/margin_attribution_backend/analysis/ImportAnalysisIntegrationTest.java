package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisRunController;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse;
import dev.margintrace.margin_attribution_backend.analysis.service.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionPolicy;
import dev.margintrace.margin_attribution_backend.algorithm.workflow.*;
import dev.margintrace.margin_attribution_backend.datalake.storage.*;
import dev.margintrace.margin_attribution_backend.datalake.model.StoredObject;
import dev.margintrace.margin_attribution_backend.importation.controller.ImportJobController;
import dev.margintrace.margin_attribution_backend.importation.service.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.time.LocalDate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Three small XLSX uploads through the real import/warehouse/HTTP pipeline; only object storage is isolated. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:import-analysis;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "margin.embedding.python=${MARGIN_EMBEDDING_PYTHON:}",
        "margin.embedding.model=${MARGIN_EMBEDDING_MODEL:}",
        "margin.embedding.revision=${MARGIN_EMBEDDING_REVISION:}"})
class ImportAnalysisIntegrationTest {
    @Autowired ImportService imports;
    @Autowired ImportJobStateService jobs;
    @Autowired AnalysisRunService analysis;
    @Autowired AnalysisSourceLoader sources;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean RawFileStorage storage;
    @MockitoBean RawFileReader reader;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void importTinyFixture() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS raw");
        jdbc.update("DELETE FROM inventory_usage"); jdbc.update("DELETE FROM sales_details"); jdbc.update("DELETE FROM material_master");
        var objects = new ConcurrentHashMap<String, byte[]>();
        when(storage.store(any(), anyString())).thenAnswer(call -> {
            var file = (org.springframework.web.multipart.MultipartFile) call.getArgument(0);
            String key = call.getArgument(1); objects.put(key, file.getBytes());
            return new StoredObject("isolated-test", key, "test", file.getSize());
        });
        when(reader.readFile(anyString())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0))));
        upload("material_master", new String[]{"Material No","Material Name","Specification","Material Group","Base Uom","Supplier","Source Kind","Unit Cost"},
                new Object[][]{{"A","Enclosure","Assembled enclosure","Finished product","each",null,"INTERNAL",3},
                        {"R1","Steel sheet","SPCC 2 mm","Steel","kg","Supplier A","PURCHASED",2},
                        {"R2","Bolt","M6 x 20 mm","Fastener","each","Supplier B","PURCHASED",1}});
        upload("sales_details", new String[]{"Date","Movement No","Product No","Product Quantity","Total Revenue","Total Cost"},
                new Object[][]{{"2025-01-15","SALE0","A",10,100,30},{"2026-01-15","SALE1","A",10,100,50}});
        upload("inventory_usage", new String[]{"Date","Movement No","Order No","Product No","Product Num","Product Total Cost","Material No","Material Num","Material Total Cost"},
                new Object[][]{{"2025-01-15","ISSUE01","P0","A",10,30,"R1",10,20},
                        {"2025-01-15","ISSUE02","P0","A",10,30,"R2",10,10},
                        {"2026-01-15","ISSUE11","P1","A",10,50,"R1",10,40},
                        {"2026-01-15","ISSUE12","P1","A",10,50,"R2",10,10}});
    }

    private void upload(String target, String[] headings, Object[][] rows) throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(target); var header = sheet.createRow(0);
            for (int c=0;c<headings.length;c++) header.createCell(c).setCellValue(headings[c]);
            for (int r=0;r<rows.length;r++) {
                var row = sheet.createRow(r+1);
                for (int c=0;c<headings.length;c++) {
                    Object value=rows[r][c]; if(value==null) continue;
                    var cell=row.createCell(c);
                    if(value instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(value.toString());
                }
            }
            workbook.write(output); bytes=output.toByteArray();
        }
        var samples=Path.of("target/import-analysis-validation/files"); Files.createDirectories(samples);
        Files.write(samples.resolve(target+".xlsx"),bytes);
        var mvc=MockMvcBuilders.standaloneSetup(new ImportJobController(imports,jobs)).build();
        var result=mvc.perform(multipart("/imports").file(new MockMultipartFile("file",target+".xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",bytes))
                .param("mappingTableName",target).param("mappingResult","true")).andExpect(status().isAccepted()).andReturn();
        long id=mapper.readTree(result.getResponse().getContentAsString()).get("jobId").asLong();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(!jobs.getRequiredJob(id).getStatus().isTerminal() && System.nanoTime()<deadline) Thread.sleep(20);
        var job=jobs.getRequiredJob(id);
        assertEquals("WRITE_SUCCESS",job.getStatus().name(),job.getErrorMessage());
        assertEquals(rows.length,job.getImportedRows());
        mvc.perform(get("/imports/"+id)).andExpect(status().isOk()).andExpect(jsonPath("$.importedRows").value(rows.length));
    }

    private AnalysisCommand command() {
        var config=new FgwEngine.Config(); config.captureTrace=true;
        var options=new AnalysisCommand.WarehouseOptions("tiny-import", "NZD", "recorded valuation",
                ProductionTree.OutputInterpretation.REPEATED_INSTANCE_TOTAL, ProductionTree.OutputInterpretation.REPEATED_INSTANCE_TOTAL);
        return new AnalysisCommand(LocalDate.of(2026,1,1),LocalDate.of(2026,1,31),LocalDate.of(2025,1,1),LocalDate.of(2025,1,31),
                new java.math.BigDecimal("5"),new java.math.BigDecimal("15"),ExecutionPolicy.cpu(),EmbeddingSettings.cpu(),
                new AnalysisCommand.Comparison(null,true,Map.of(),Map.of(),config,options));
    }

    @Test void importedRecordsReachRealModelAndFgwThroughHttpAndStreaming() throws Exception {
        Assumptions.assumeTrue(System.getenv("MARGIN_EMBEDDING_PYTHON")!=null,"Real model runtime not configured; UNVERIFIED");
        var frozen=sources.loadSnapshot(true); assertEquals(3,frozen.materials().size()); assertEquals(4,frozen.records().issues().size());
        var mvc=MockMvcBuilders.standaloneSetup(new AnalysisRunController(analysis)).build();
        var response=mvc.perform(post("/api/v1/analysis/run").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.planned").value(1)).andExpect(jsonPath("$.executed").value(1))
                .andExpect(jsonPath("$.embeddings.unresolved").value(0)).andReturn();
        var outcome=mapper.readTree(response.getResponse().getContentAsString());
        assertEquals(6,outcome.get("embeddings").get("vectors").size());
        assertEquals(6,outcome.get("embeddings").get("manifests").size());
        var trees=outcome.get("selection").get("trees");
        assertEquals(2,trees.size()); assertEquals("P0",trees.get(0).get("productionId").asText());
        for(var tree:trees) {assertEquals(3,tree.get("nodes").size());assertEquals(2,tree.get("edges").size());assertTrue(tree.get("directComplete").asBoolean());}
        var result=outcome.get("pairs").get(0).get("comparison").get("result");
        assertEquals(3,result.get("rows").asInt());assertEquals(3,result.get("columns").asInt());
        assertEquals(9,result.get("starts").get(result.get("bestStart").asInt()).get("correspondence").size());
        var stream=mvc.perform(post("/api/v1/analysis/run/stream").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(command())))
                .andExpect(request().asyncStarted()).andReturn();
        stream.getAsyncResult(120000);
        var content=mvc.perform(asyncDispatch(stream)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var events=content.lines().map(mapper::readTree).toList();
        assertEquals(List.of("stage","graph","stage","branches","stage","production-trees","stage","embeddings","stage","comparison","completed"),events.stream().map(e->e.get("type").asText()).toList());
        assertEquals(1,events.get(8).get("data").get("total").asInt());
        for(int i=0;i<events.size();i++) { assertEquals(i+1,events.get(i).get("sequence").asInt());assertEquals(events.get(0).get("analysisId"),events.get(i).get("analysisId")); }
        assertEquals(1,events.getLast().get("data").get("executed").asInt());
        // Capture the immutable real numerical inputs for the independent CPU/POT oracle.
        var actual=analysis.run(command(),event->{});var pair=actual.pairs().getFirst().comparison();
        var dir=Path.of("target/import-analysis-validation");Files.createDirectories(dir);
        mapper.writeValue(dir.resolve("numerical-inputs.json").toFile(),Map.of("left",ProductionTreeFgwAdapter.graph(actual.selection().trees().get(0),actual.embeddings().vectors()),
                "right",ProductionTreeFgwAdapter.graph(actual.selection().trees().get(1),actual.embeddings().vectors()),"result",pair.result(),
                "config",command().comparison().config(),"starts",List.of("UNIFORM_OUTER_PRODUCT"),"leafThreshold","5","stopThreshold","15"));
        Files.writeString(dir.resolve("outcome.json"),AnalysisRunResponse.json(actual).toString());
    }

    @Test void missingSupplierRemainsVisibleWithoutFabricatedVectors() {
        jdbc.update("UPDATE material_master SET supplier=NULL WHERE material_no='R1'");
        var outcome=analysis.run(command(),event->{});
        assertEquals(1,outcome.planned());assertEquals(0,outcome.executed());
        assertTrue(outcome.embeddings().manifests().stream().anyMatch(m->m.statuses().contains("MISSING_EMBEDDING_CONTEXT:supplier")));
    }
}
