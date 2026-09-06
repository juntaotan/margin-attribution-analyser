package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.attribution.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.TopologicalSort;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.impl.AnalysisServiceImpl;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisServiceImplTests {

    @Mock
    private ProductionRepository productionRepository;

    @Mock
    private AttributionPartitionReader reader;

    @Mock
    private TopologicalSort topologicalSort;

    @InjectMocks
    private AnalysisServiceImpl analysisService;

    @Test
    void returnsEmptyResponseWhenDatesAreMissing() {
        AnalysisRequest request = AnalysisRequest.builder().build();
        AnalysisGraphResponse response = analysisService.analyze(request);

        assertThat(response.getNodes()).isEmpty();
        assertThat(response.getEdges()).isEmpty();
        assertThat(response.getSummary().get("message")).isEqualTo("Start date and end date are required");
    }

    @Test
    void returnsEmptyResponseWhenNoProductionsFound() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        AnalysisRequest request = AnalysisRequest.builder()
                .startDate(from)
                .endDate(to)
                .build();

        when(productionRepository.findAllByDateBetweenOrderByDateAsc(from, to))
                .thenReturn(List.of());

        AnalysisGraphResponse response = analysisService.analyze(request);

        assertThat(response.getNodes()).isEmpty();
        assertThat(response.getEdges()).isEmpty();
        assertThat(response.getSummary().get("message")).isEqualTo("No production records found for the given period");
    }

    @Test
    void returnsGraphResponseWhenProductionsAndMaterialsExist() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 31);
        AnalysisRequest request = AnalysisRequest.builder()
                .startDate(from)
                .endDate(to)
                .build();

        Production prod = Production.of(
                "PO-001",
                from,
                "PROD-A",
                new BigDecimal("100"),
                "Machining",
                "BOM-001"
        );
        // Note: Production ID will be null unless simulated or handled, or let's test id or mock
        when(productionRepository.findAllByDateBetweenOrderByDateAsc(from, to))
                .thenReturn(List.of(prod));

        Node matNode = new Node("MAT-1", new BigDecimal("50"));
        Node prodNode = new Node("PROD-A", new BigDecimal("100"));
        Map<Node, List<Node>> usage = Map.of(matNode, List.of(prodNode));

        when(reader.readMaterialUsage(0L, 0L)).thenReturn(usage);

        CsrGraph csrGraph = new CsrGraph(
                new Node[]{matNode, prodNode},
                new int[]{0, 1, 1},
                new int[]{1}
        );
        when(topologicalSort.offsetDependencies(usage)).thenReturn(csrGraph);

        AnalysisGraphResponse response = analysisService.analyze(request);

        assertThat(response.getNodes()).hasSize(2);
        assertThat(response.getEdges()).hasSize(1);
        assertThat(response.getEdges().get(0).getSource()).isEqualTo("MAT-1");
        assertThat(response.getEdges().get(0).getTarget()).isEqualTo("PROD-A");
    }
}
