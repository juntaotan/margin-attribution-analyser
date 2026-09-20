package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationAnalysisResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationStreamEvent;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.SalesOrderLineRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyserTests {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Mock private AttributionWorkflow attributionWorkflow;
    @Mock private BillOfMaterialRepository billOfMaterialRepository;
    @Mock private ProductionRepository productionRepository;
    @Mock private SalesOrderLineRepository salesOrderLineRepository;
    @InjectMocks private Analyser analyser;

    @Test
    void streamsTheSameCsrPositionsAndIdsUsedByThresholdPaths() {
        LocalDate comparableStart = LocalDate.of(2025, 1, 1);
        LocalDate comparableEnd = LocalDate.of(2025, 1, 31);
        CsrGraph actual = new CsrGraph(new Node[] {
                node("M", 1, "10"), node("S", 1, "5"), node("P", 1, "20")},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});
        CsrGraph comparable = new CsrGraph(new Node[] {
                node("P", 1, "5"), node("S", 1, "5"), node("M", 1, "0")},
                new int[] {0, 0, 1, 2}, new int[] {0, 1});
        when(attributionWorkflow.trace(START, END)).thenReturn(actual);
        when(attributionWorkflow.trace(comparableStart, comparableEnd)).thenReturn(comparable);
        UUID analysisId = UUID.randomUUID();
        List<ReconciliationStreamEvent> events = new ArrayList<>();

        analyser.streamReconciliation(START, END, comparableStart, comparableEnd,
                BigDecimal.TEN, BigDecimal.TEN, analysisId, events::add);

        assertThat(events).extracting(ReconciliationStreamEvent::type)
                .containsExactly("graph", "diff");
        assertThat(events).allMatch(event -> event.analysisId().equals(analysisId));
        var graph = events.get(0).actualGraph();
        var path = events.get(1).paths().getFirst();
        assertThat(path.positions()).containsExactly(0, 1, 2);
        assertThat(path.edgeIndexes()).containsExactly(0, 1);
        assertThat(path.nodeIds()).containsExactly(
                graph.nodes().get(0).id(), graph.nodes().get(1).id(), graph.nodes().get(2).id());
        assertThat(path.edgeIds()).containsExactlyElementsOf(graph.edgeIds());
        assertThat(graph.offset()).containsExactly(actual.offset());
        assertThat(graph.successors()).containsExactly(actual.successors());
        assertThat(graph.nodes().get(0).id()).isNotEqualTo(events.get(0).comparableGraph().nodes().get(0).id());
    }

    @Test
    void returnsCompleteThresholdPathWithActualNodeValues() {
        Node material = node("M", 1, "10");
        Node semi = node("S", 1, "5");
        Node product = node("P", 1, "20");
        CsrGraph actual = new CsrGraph(new Node[] {material, semi, product},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});
        CsrGraph comparable = new CsrGraph(new Node[] {
                node("P", 1, "5"), node("S", 1, "5"), node("M", 1, "0")},
                new int[] {0, 0, 1, 2}, new int[] {0, 1});

        ReconciliationAnalysisResponse response = analyser.reconcileGraphs(
                actual, comparable, BigDecimal.TEN, BigDecimal.TEN);

        assertThat(response.analysisId()).isNotNull();
        assertThat(response.paths()).hasSize(1);
        assertThat(response.paths().getFirst().nodes()).containsExactly(material, semi, product);
        assertThat(response.paths().getFirst().endingCostDifference()).isEqualByComparingTo("15");
    }

    @Test
    void buildsBothPeriodGraphsBeforeReconciling() {
        LocalDate comparableStart = LocalDate.of(2025, 1, 1);
        LocalDate comparableEnd = LocalDate.of(2025, 1, 31);
        CsrGraph actual = new CsrGraph(new Node[] {node("M", 1, "10"), node("P", 1, "20")},
                new int[] {0, 1, 1}, new int[] {1});
        CsrGraph comparable = new CsrGraph(new Node[] {node("M", 1, "0"), node("P", 1, "0")},
                new int[] {0, 1, 1}, new int[] {1});
        when(attributionWorkflow.trace(START, END)).thenReturn(actual);
        when(attributionWorkflow.trace(comparableStart, comparableEnd)).thenReturn(comparable);

        ReconciliationAnalysisResponse response = analyser.reconcilePeriods(
                START, END, comparableStart, comparableEnd, BigDecimal.TEN, BigDecimal.TEN);

        assertThat(response.paths()).hasSize(1);
        assertThat(response.paths().getFirst().nodes()).containsExactly(actual.nodes());
    }

    @Test
    void combinesOnlyTracedEdgesAcrossTargetsAndRemovesDuplicates() {
        Node material = node("MATERIAL", 10, "120.00");
        Node component = node("COMPONENT", 5, null);
        Node firstTarget = node("TARGET-A", 2, null);
        Node otherMaterial = node("OTHER-MATERIAL", 3, "15.00");
        Node secondTarget = node("TARGET-B", 1, null);
        Node unrelated = node("UNRELATED", 7, null);
        CsrGraph graph = new CsrGraph(
                new Node[] {material, component, firstTarget, otherMaterial, secondTarget, unrelated},
                new int[] {0, 1, 3, 3, 4, 4, 4},
                new int[] {1, 2, 4, 2});
        when(attributionWorkflow.trace(START, END))
                .thenReturn(graph);
        when(salesOrderLineRepository.findAllByDateBetweenOrderByDateAscIdAsc(START, END))
                .thenReturn(List.of(sale("TARGET-A"), sale("TARGET-B")));

        AnalysisResults result = analyser.analyser(START, END);

        assertThat(result.getAnalysisId()).isNotNull();
        assertThat(result.getResults()).containsExactly(
                new AnalysisAdjacencyEntry(material, List.of(component)),
                new AnalysisAdjacencyEntry(component, List.of(firstTarget, secondTarget)),
                new AnalysisAdjacencyEntry(firstTarget, List.of()),
                new AnalysisAdjacencyEntry(otherMaterial, List.of(firstTarget)),
                new AnalysisAdjacencyEntry(secondTarget, List.of()));
    }

    @Test
    void preservesAStandaloneTargetWithNoEdges() {
        Node target = node("TARGET", 1, null);
        CsrGraph graph = new CsrGraph(new Node[] {target}, new int[] {0, 0}, new int[] {});
        when(attributionWorkflow.trace(START, END))
                .thenReturn(graph);
        when(salesOrderLineRepository.findAllByDateBetweenOrderByDateAscIdAsc(START, END))
                .thenReturn(List.of(sale("TARGET")));

        AnalysisResults result = analyser.analyser(START, END);

        assertThat(result.getResults()).containsExactly(
                new AnalysisAdjacencyEntry(target, List.of()));
    }

    @Test
    void tracesTargetWithRecordedCost() {
        Node material = node("MATERIAL", 2, "12.00");
        Node target = node("TARGET", 1, "20.00");
        CsrGraph graph = new CsrGraph(
                new Node[] {material, target}, new int[] {0, 1, 1}, new int[] {1});
        when(attributionWorkflow.trace(START, END)).thenReturn(graph);
        when(salesOrderLineRepository.findAllByDateBetweenOrderByDateAscIdAsc(START, END))
                .thenReturn(List.of(sale("TARGET")));

        AnalysisResults result = analyser.analyser(START, END);

        assertThat(result.getResults()).containsExactly(
                new AnalysisAdjacencyEntry(material, List.of(target)),
                new AnalysisAdjacencyEntry(target, List.of()));
    }

    @Test
    void traceBomBuildsMultiLevelBomAdjacencyWithSubMaterialsUpstream() {
        BillOfMaterial bomA1 = BillOfMaterial.of("BOM-A", "PROD-A", "SEMI-B", new BigDecimal("1.000000"));
        BillOfMaterial bomB1 = BillOfMaterial.of("BOM-B", "SEMI-B", "MAT-D", new BigDecimal("2.500000"));

        when(billOfMaterialRepository.findAllByProductNoIn(Set.of("PROD-A"))).thenReturn(List.of(bomA1));
        when(billOfMaterialRepository.findAllByProductNoIn(Set.of("SEMI-B"))).thenReturn(List.of(bomB1));
        when(billOfMaterialRepository.findAllByProductNoIn(Set.of("MAT-D"))).thenReturn(List.of());

        AnalysisResults result = analyser.traceBom(List.of("PROD-A"));

        Node targetNode = new Node("PROD-A", BigDecimal.ONE);
        Node semiNode = new Node("SEMI-B", new BigDecimal("1.000000"));
        Node semiParentNode = new Node("SEMI-B", BigDecimal.ONE);
        Node matDNode = new Node("MAT-D", new BigDecimal("2.500000"));

        assertThat(result.getAnalysisId()).isNotNull();
        assertThat(result.getResults()).contains(
                new AnalysisAdjacencyEntry(targetNode, List.of()),
                new AnalysisAdjacencyEntry(semiNode, List.of(targetNode)),
                new AnalysisAdjacencyEntry(matDNode, List.of(semiParentNode)));
    }

    /** Creates a node with its original recorded quantity and optional cost. */
    private Node node(String inventoryId, long quantity, String cost) {
        return new Node(
                inventoryId,
                BigDecimal.valueOf(quantity),
                cost == null ? null : new BigDecimal(cost));
    }

    private SalesOrderLine sale(String productNo) {
        return SalesOrderLine.of("SO-" + productNo, START, "MOVE-" + productNo,
                productNo, BigDecimal.ONE, BigDecimal.ONE);
    }
}
