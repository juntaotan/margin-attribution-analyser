package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.analysis.controller.AnalysisController;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTests {

    @Mock
    private AnalysisService analysisService;

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
}
