package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.LocalLlamaClient;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootCauseReportServiceTests {
    private static final LocalDate CURRENT = LocalDate.of(2026, 1, 1);
    private static final LocalDate PREVIOUS = LocalDate.of(2025, 1, 1);

    @Mock private AttributionWorkflow workflow;
    @Mock private ProductionRepository productions;
    @Mock private BillOfMaterialRepository boms;
    @Mock private LocalLlamaClient llama;
    @InjectMocks private RootCauseReportService service;

    @Test
    void equalQuantityAndTopologyWithHigherCostIsUnitCostChange() {
        stubGraphs(graph(new Node("MAT", decimal("150"), decimal("3000")),
                        new Node("PRODUCT", decimal("100"), null)),
                graph(new Node("MAT", decimal("150"), decimal("2800")),
                        new Node("PRODUCT", decimal("100"), null)));

        var report = service.report(request("MAT"));

        assertThat(report.category()).isEqualTo("UNIT_COST_CHANGE");
        assertThat(report.aiGenerated()).isFalse();
        assertThat(report.categoryLabel()).isEqualTo("Unit cost change");
        assertThat(report.evidence()).anyMatch(line -> line.contains("Unit cost"));
    }

    @Test
    void quantityAboveBomPlanIsOveruse() {
        stubGraphs(graph(new Node("MAT", decimal("180"), decimal("3600")),
                        new Node("PRODUCT", decimal("100"), null)),
                graph(new Node("MAT", decimal("150"), decimal("3000")),
                        new Node("PRODUCT", decimal("100"), null)));
        when(productions.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc("PRODUCT", CURRENT, CURRENT))
                .thenReturn(List.of(Production.of("ORDER-26", CURRENT, "PRODUCT", decimal("100"), "D", "BOM-1")));
        when(boms.findAllByBomNoAndProductNo("BOM-1", "PRODUCT"))
                .thenReturn(List.of(BillOfMaterial.of("BOM-1", "PRODUCT", "MAT", decimal("1.5"))));

        var report = service.report(request("MAT"));

        assertThat(report.category()).isEqualTo("OVERUSE");
        assertThat(report.evidence()).anyMatch(line -> line.contains("above plan"));
    }

    @Test
    void swappedMaterialWithSameBomIsOnlySuspectedReplacement() {
        CsrGraph current = graph(new Node("NEW", decimal("10"), decimal("100")),
                new Node("PRODUCT", decimal("10"), null));
        CsrGraph previous = graph(new Node("OLD", decimal("10"), decimal("100")),
                new Node("PRODUCT", decimal("10"), null));
        stubGraphs(current, previous);

        var report = service.report(request("NEW"));

        assertThat(report.category()).isEqualTo("MATERIAL_REPLACEMENT");
        assertThat(report.certainty()).isEqualTo("Needs verification");
    }

    @Test
    void changedBomLinesTakePriorityOverObservedMaterialSwap() {
        stubGraphs(graph(new Node("NEW", decimal("10"), decimal("100")),
                        new Node("PRODUCT", decimal("10"), null)),
                graph(new Node("OLD", decimal("10"), decimal("100")),
                        new Node("PRODUCT", decimal("10"), null)));
        when(productions.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc("PRODUCT", CURRENT, CURRENT))
                .thenReturn(List.of(Production.of("ORDER-26", CURRENT, "PRODUCT", decimal("10"), "D", "BOM-2")));
        when(productions.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc("PRODUCT", PREVIOUS, PREVIOUS))
                .thenReturn(List.of(Production.of("ORDER-25", PREVIOUS, "PRODUCT", decimal("10"), "D", "BOM-1")));
        when(boms.findAllByBomNoAndProductNo("BOM-2", "PRODUCT"))
                .thenReturn(List.of(BillOfMaterial.of("BOM-2", "PRODUCT", "NEW", decimal("1"))));
        when(boms.findAllByBomNoAndProductNo("BOM-1", "PRODUCT"))
                .thenReturn(List.of(BillOfMaterial.of("BOM-1", "PRODUCT", "OLD", decimal("1"))));

        var report = service.report(request("NEW"));

        assertThat(report.category()).isEqualTo("BOM_CHANGE");
        assertThat(report.certainty()).isEqualTo("Confirmed by records");
    }

    private void stubGraphs(CsrGraph current, CsrGraph previous) {
        when(workflow.trace(CURRENT, CURRENT)).thenReturn(current);
        when(workflow.trace(PREVIOUS, PREVIOUS)).thenReturn(previous);
        when(llama.explain(anyString(), anyList()))
                .thenReturn(new LocalLlamaClient.Result(null, false, "model unavailable"));
    }

    private RootCauseReportRequest request(String inventoryId) {
        return new RootCauseReportRequest(CURRENT, CURRENT, PREVIOUS, PREVIOUS, inventoryId);
    }

    private CsrGraph graph(Node material, Node product) {
        return new CsrGraph(new Node[] {material, product}, new int[] {0, 1, 1}, new int[] {1});
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
}
