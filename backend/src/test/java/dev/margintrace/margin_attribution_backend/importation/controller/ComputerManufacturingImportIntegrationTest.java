package dev.margintrace.margin_attribution_backend.importation.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.margintrace.margin_attribution_backend.datalake.model.StoredObject;
import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileStorage;
import dev.margintrace.margin_attribution_backend.importation.service.ImportJobStateService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports the complete generated computer fixture through multipart controllers
 * and the real asynchronous handler chain into a dedicated H2 database. Only
 * raw object storage is replaced by owned memory. Enable with
 * -Dcomputer.import.fixture=/absolute/path/to/xlsx/input.json. Every imported
 * value is compared with the original CSV strings, including decimal scale.
 */
@EnabledIfSystemProperty(named = "computer.import.fixture", matches = ".+")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:computer-import;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false",
            "server.address=127.0.0.1"
        })
class ComputerManufacturingImportIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    ImportJobStateService jobs;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    RawFileStorage storage;

    @MockitoBean
    RawFileReader reader;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final List<String> TABLES =
            List.of("material_master", "bill_of_material", "inventory_usage", "purchases", "sales_details");
    private static final Set<String> NUMERIC = Set.of(
            "unit_cost",
            "material_usage",
            "product_num",
            "material_num",
            "product_total_cost",
            "material_total_cost",
            "product_total_price",
            "total_revenue",
            "total_cost");

    @DynamicPropertySource
    static void productionUploadLimits(DynamicPropertyRegistry registry) throws Exception {
        // Test resources replace application.properties; copy only the actual
        // production upload limits while retaining explicit isolated connections.
        var properties = new Properties();
        try (var stream = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(stream);
        }
        for (String key :
                List.of("spring.servlet.multipart.max-file-size", "spring.servlet.multipart.max-request-size")) {
            if (properties.containsKey(key)) registry.add(key, () -> properties.getProperty(key));
        }
    }

    @Test
    void allGeneratedExcelRowsReachWarehouseWithoutValueLoss() throws Exception {
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS raw");
        var objects = new ConcurrentHashMap<String, byte[]>();
        when(storage.store(any(), anyString())).thenAnswer(call -> {
            var file = (org.springframework.web.multipart.MultipartFile) call.getArgument(0);
            String key = call.getArgument(1);
            objects.put(key, file.getBytes());
            return new StoredObject("isolated-computer-import", key, "test", file.getSize());
        });
        when(reader.readFile(anyString()))
                .thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(0))));
        var client = HttpClient.newHttpClient();
        Path input = Path.of(System.getProperty("computer.import.fixture"));
        var fixture = mapper.readTree(Files.readString(input));
        var report = new ArrayList<Map<String, Object>>();
        for (String table : TABLES) {
            var data = fixture.get(table);
            var headers = new ArrayList<String>();
            for (var header : data.get("headers")) {
                String column = header.asText();
                assertTrue(column.matches("[a-z_]+"));
                headers.add(column);
            }
            byte[] bytes = Files.readAllBytes(input.resolveSibling(table + ".xlsx"));
            try (var workbook = org.apache.poi.ss.usermodel.WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                assertEquals(data.get("rows").size(), workbook.getSheetAt(0).getLastRowNum(), table + " XLSX rows");
            }
            long start = System.nanoTime();
            String boundary = "computer-import-" + table;
            String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"mappingTableName\"\r\n\r\n"
                    + table + "\r\n--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"mappingResult\"\r\n\r\ntrue\r\n--"
                    + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + table
                    + ".xlsx\"\r\n"
                    + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n";
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/imports"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(
                            HttpRequest.BodyPublishers.ofByteArray(prefix.getBytes(StandardCharsets.UTF_8)),
                            HttpRequest.BodyPublishers.ofByteArray(bytes),
                            HttpRequest.BodyPublishers.ofByteArray(
                                    ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8))))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(202, response.statusCode(), table + " upload: " + response.body());
            long id = mapper.readTree(response.body()).get("jobId").asLong();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!jobs.getRequiredJob(id).getStatus().isTerminal() && System.nanoTime() < deadline) Thread.sleep(100);
            var job = jobs.getRequiredJob(id);
            assertEquals("WRITE_SUCCESS", job.getStatus().name(), table + ": " + job.getErrorMessage());
            assertEquals(data.get("rows").size(), job.getImportedRows(), table);
            assertEquals(0, job.getRejectedRows());
            assertArrayEquals(bytes, objects.get(job.getStorageObjectKey()));
            var status = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/imports/" + id))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, status.statusCode());
            assertEquals(
                    data.get("rows").size(),
                    mapper.readTree(status.body()).get("importedRows").asInt());
            var expected = new HashMap<List<String>, Integer>();
            for (var row : data.get("rows")) {
                var values = new ArrayList<String>();
                for (int c = 0; c < headers.size(); c++)
                    values.add(canonical(row.get(c).isNull() ? null : row.get(c).asText(), headers.get(c)));
                expected.merge(values, 1, Integer::sum);
            }
            var actual = new HashMap<List<String>, Integer>();
            jdbc.query("SELECT " + String.join(",", headers) + " FROM " + table, rs -> {
                var values = new ArrayList<String>();
                for (int c = 0; c < headers.size(); c++) values.add(canonical(rs.getString(c + 1), headers.get(c)));
                actual.merge(values, 1, Integer::sum);
            });
            // Multiset comparison catches missing/extra rows and all mapped field changes.
            assertEquals(expected.size(), actual.size(), table + " distinct warehouse rows");
            for (var entry : expected.entrySet())
                assertEquals(
                        entry.getValue(),
                        actual.get(entry.getKey()),
                        table + " warehouse values differ for " + entry.getKey());
            report.add(Map.of(
                    "table",
                    table,
                    "rows",
                    job.getImportedRows(),
                    "status",
                    job.getStatus().name(),
                    "milliseconds",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    "fileBytes",
                    bytes.length,
                    "allValuesMatch",
                    true));
        }
        assertEquals(
                fixture.get("sales_details").get("rows").size(),
                jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT order_no) FROM inventory_usage WHERE product_no LIKE 'PC-%'",
                        Integer.class));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM (SELECT order_no FROM inventory_usage GROUP BY order_no HAVING COUNT(DISTINCT product_no)>1) conflicts",
                        Integer.class));
        Path reportPath = Path.of("target/computer-import-validation/report.json");
        Files.createDirectories(reportPath.getParent());
        mapper.writeValue(
                reportPath.toFile(),
                Map.of(
                        "status",
                        "PASS",
                        "tables",
                        report,
                        "database",
                        "isolated H2 PostgreSQL mode",
                        "objectStorage",
                        "owned memory",
                        "scope",
                        "Real HTTP multipart import, asynchronous pipeline, warehouse writes and exact CSV value reconciliation"));
    }

    private static String canonical(String value, String column) {
        if (value == null || value.isEmpty()) return "";
        return NUMERIC.contains(column)
                ? new BigDecimal(value).stripTrailingZeros().toPlainString()
                : value;
    }
}
