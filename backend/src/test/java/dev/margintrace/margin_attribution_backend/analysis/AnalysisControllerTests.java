package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisController;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTests {

    @Mock
    private AnalysisService analysisService;

    @Mock
    private Analyser analyser;

    @InjectMocks
    private AnalysisController analysisController;

    @Test
    void postMarginTopologyReturnsGraphResponse() {
        AnalysisRequest request = AnalysisRequest.builder().build();
        AnalysisGraphResponse mockResponse = AnalysisGraphResponse.builder().build();
        when(analysisService.analyze(request)).thenReturn(mockResponse);

        ResponseEntity<AnalysisGraphResponse> response = analysisController.analyzeMarginTopology(request);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isSameAs(mockResponse);
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
        when(analyser.analyser(List.of("PROD-A"), startDate, endDate)).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-01-01","endDate":"2026-01-31","targets":["PROD-A"]}
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
        when(analyser.analyser(List.of("MISSING"), startDate, endDate))
                .thenThrow(new IllegalArgumentException("Target not found in period: MISSING"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(analysisController).build();

        mvc.perform(post("/api/v1/analysis/trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":"2026-01-01","endDate":"2026-01-31","targets":["MISSING"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Target not found in period: MISSING"));
    }
}
