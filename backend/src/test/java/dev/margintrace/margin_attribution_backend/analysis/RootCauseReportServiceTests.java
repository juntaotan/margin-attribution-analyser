package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.ai.RootCauseAssistant;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RootCauseReportServiceTests {
    private static final LocalDate CURRENT = LocalDate.of(2026, 1, 1);
    private static final LocalDate PREVIOUS = LocalDate.of(2025, 1, 1);

    @Mock private AttributionWorkflow workflow;
    @Mock private InventoryUsageRepository inventoryUsage;
    @Mock private BillOfMaterialRepository boms;
    @Mock private RootCauseAssistant rootCauseAssistant;
    @Mock private AiErrorMapper aiErrorMapper;
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
        when(inventoryUsage.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc("PRODUCT", CURRENT, CURRENT))
                .thenReturn(List.of(InventoryUsage.of(CURRENT, "MOVE-26", "PRODUCT", decimal("100"), null, "ORDER-26")));
        when(inventoryUsage.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc("PRODUCT", PREVIOUS, PREVIOUS))
                .thenReturn(List.of(InventoryUsage.of(PREVIOUS, "MOVE-25", "PRODUCT", decimal("100"), null, "ORDER-25")));
        when(boms.findAllByProductNoIn(java.util.Set.of("PRODUCT")))
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

    private void stubGraphs(CsrGraph current, CsrGraph previous) {
        when(workflow.trace(CURRENT, CURRENT)).thenReturn(current);
        when(workflow.trace(PREVIOUS, PREVIOUS)).thenReturn(previous);
        when(rootCauseAssistant.summarize(anyString(), anyString())).thenReturn(null);
    }

    private RootCauseReportRequest request(String inventoryId) {
        return new RootCauseReportRequest(CURRENT, CURRENT, PREVIOUS, PREVIOUS, inventoryId);
    }

    private CsrGraph graph(Node material, Node product) {
        return new CsrGraph(new Node[] {material, product}, new int[] {0, 1, 1}, new int[] {1});
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
}
