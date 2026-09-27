package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.BlueprintInputField;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportPlaceholderControllerTests {

    @Mock
    private ReportPlaceholderService placeholderService;
    @Mock
    private OnlyOfficeController onlyOfficeController;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ReportPlaceholderController controller =
                new ReportPlaceholderController(placeholderService, onlyOfficeController);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConfigurationsReturnsList() throws Exception {
        UUID configId = UUID.randomUUID();
        when(placeholderService.findConfigurations("default", null)).thenReturn(List.of(
                new ReportPlaceholderService.PlaceholderConfigDto(
                        configId,
                        "default",
                        "margintrace:rev",
                        "Revenue",
                        "prompt",
                        "2026",
                        sampleBlueprint(),
                        "SELECT 1",
                        "currency",
                        1L,
                        Instant.now(),
                        Instant.now(),
                        null
                )
        ));

        mvc.perform(get("/api/report-studio/placeholders?documentId=default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(configId.toString()))
                .andExpect(jsonPath("$[0].tag").value("margintrace:rev"))
                .andExpect(jsonPath("$[0].alias").value("Revenue"))
                .andExpect(jsonPath("$[0].format").value("currency"))
                .andExpect(jsonPath("$[0].revision").value(1));
    }

    @Test
    void generateEndpointCallsServiceAndReturnsResult() throws Exception {
        UUID configId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ReportBlueprint blueprint = sampleBlueprint();
        ReportQueryService.QueryResult queryResult = new ReportQueryService.QueryResult(
                "SELECT 1", List.of("result"), List.of(Map.of("result", 100)), 1);

        when(placeholderService.generate(any(ReportPlaceholderService.GenerateRequest.class))).thenReturn(
                new ReportPlaceholderService.GenerateResult(
                        configId,
                        runId,
                        1L,
                        blueprint,
                        queryResult,
                        null
                )
        );

        mvc.perform(post("/api/report-studio/placeholders/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "documentId": "default",
                                  "tag": "margintrace:rev",
                                  "alias": "Revenue",
                                  "prompt": "Duration: 2026\\nTotal rev"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configId").value(configId.toString()))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.blueprint.sourceTable").value("sales_order"))
                .andExpect(jsonPath("$.result.rowCount").value(1));
    }

    @Test
    void applyPlaceholderCallsServiceAndInvalidatesKeys() throws Exception {
        UUID runId = UUID.randomUUID();
        when(placeholderService.applyRun(eq(runId), eq("Revenue"))).thenReturn(
                new ReportPlaceholderService.ApplyResult(4L, 2, runId, "$1,250,000")
        );

        mvc.perform(post("/api/report-studio/placeholders/apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "runId": "%s",
                                  "alias": "Revenue"
                                }
                                """.formatted(runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentVersion").value(4))
                .andExpect(jsonPath("$.updatedControls").value(2))
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.appliedValue").value("$1,250,000"));

        verify(onlyOfficeController).invalidateIssuedDocumentKeys();
    }

    @Test
    void executeRunCallsService() throws Exception {
        UUID runId = UUID.randomUUID();
        when(placeholderService.executeRun(runId)).thenReturn(
                new ReportQueryService.QueryResult("SELECT 1", List.of("result"), List.of(Map.of("result", 50)), 1)
        );

        mvc.perform(post("/api/report-studio/query-runs/" + runId + "/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.rows[0].result").value(50));
    }

    private ReportBlueprint sampleBlueprint() {
        return new ReportBlueprint(
                "sales_order",
                "Sales orders",
                List.of(),
                List.of(new BlueprintInputField("product_total_price", "Revenue", "desc")),
                "SUM(product_total_price)",
                "Total rev",
                "currency",
                "explanation");
    }
}
