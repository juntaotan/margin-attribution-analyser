package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisController;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationAnalysisResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationPathEntry;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTests {

    @Mock
    private Analyser analyser;

    @InjectMocks
    private AnalysisController analysisController;

    @Test
    void reconcilePeriodsSerializesCompleteThresholdPath() throws Exception {
        Node material = new Node("M", BigDecimal.ONE, BigDecimal.TEN);
        Node product = new Node("P", BigDecimal.ONE, BigDecimal.valueOf(20));
        ReconciliationAnalysisResponse expected = new ReconciliationAnalysisResponse(
                UUID.randomUUID(), List.of(new ReconciliationPathEntry(
                        List.of(material, product), List.of(new GraphEdge(0, 1, 0)),
                        PropagationPath.EndReason.THRESHOLD_EXCEEDED, BigDecimal.valueOf(15))));
        when(analyser.reconcilePeriods(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31),
                BigDecimal.TEN, BigDecimal.TEN)).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/reconcile-periods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"actualStartDate":"2026-01-01","actualEndDate":"2026-01-31",
                                 "comparableStartDate":"2025-01-01","comparableEndDate":"2025-01-31",
                                 "leafThreshold":10,"stopThreshold":10}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths[0].nodes[0].inventoryId").value("M"))
                .andExpect(jsonPath("$.paths[0].nodes[1].inventoryId").value("P"))
                .andExpect(jsonPath("$.paths[0].edges[0].edgeIndex").value(0))
                .andExpect(jsonPath("$.paths[0].endingCostDifference").value(15));
    }

    @Test
    void traceEndpointAcceptsDatesAndSerializesRecordedNodeValues() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 1, 31);
        Node material = new Node("MAT-1", new BigDecimal("4"), new BigDecimal("123.45"));
        Node product = new Node("PROD-A", new BigDecimal("10"), null);
        AnalysisResults expected = AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.of(
                        new AnalysisAdjacencyEntry(material, List.of(product)),
                        new AnalysisAdjacencyEntry(product, List.of())))
                .build();
        when(analyser.analyser(startDate, endDate)).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-01-01","endDate":"2026-01-31"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].upstream.inventoryId").value("MAT-1"))
                .andExpect(jsonPath("$.results[0].upstream.quantity").value(4))
                .andExpect(jsonPath("$.results[0].upstream.cost").value(123.45))
                .andExpect(jsonPath("$.results[0].downstream[0].inventoryId").value("PROD-A"))
                .andExpect(jsonPath("$.results[1].upstream.cost").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.results[1].downstream").isEmpty());
    }

    @Test
    void traceEndpointReturnsReadableBadRequestForMissingTarget() throws Exception {
        LocalDate startDate = LocalDate.of(2026, 1, 1);
        LocalDate endDate = LocalDate.of(2026, 1, 31);
        when(analyser.analyser(startDate, endDate))
                .thenThrow(new IllegalArgumentException("Target not found in period: MISSING"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-01-01","endDate":"2026-01-31"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Target not found in period: MISSING"));
    }

    @Test
    void bomEndpointAcceptsTargetsAndSerializesBomAdjacency() throws Exception {
        Node material = new Node("MAT-1", new BigDecimal("2"), null);
        Node product = new Node("PROD-A", BigDecimal.ONE, null);
        AnalysisResults expected = AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.of(
                        new AnalysisAdjacencyEntry(material, List.of(product)),
                        new AnalysisAdjacencyEntry(product, List.of())))
                .build();
        when(analyser.traceBom(List.of("PROD-A"), null, null)).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/bom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"targets":["PROD-A"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].upstream.inventoryId").value("MAT-1"))
                .andExpect(jsonPath("$.results[0].upstream.quantity").value(2))
                .andExpect(jsonPath("$.results[0].downstream[0].inventoryId").value("PROD-A"))
                .andExpect(jsonPath("$.results[1].upstream.inventoryId").value("PROD-A"))
                .andExpect(jsonPath("$.results[1].downstream").isEmpty());
    }
}
