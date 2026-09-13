package dev.margintrace.margin_attribution_backend.analysis;

import dev.margintrace.margin_attribution_backend.algorithm.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyserTests {
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Mock private AttributionWorkflow attributionWorkflow;
    @Mock private BillOfMaterialRepository billOfMaterialRepository;
    @Mock private ProductionRepository productionRepository;
    @InjectMocks private Analyser analyser;

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
        Map<String, int[][]> paths = new LinkedHashMap<>();
        paths.put("TARGET-A", new int[][] {{0, 1, 2}, {3, 2}, {0, 1, 2}});
        paths.put("TARGET-B", new int[][] {{0, 1, 4}});
        when(attributionWorkflow.trace(START, END, List.of("TARGET-A", "TARGET-B")))
                .thenReturn(new CsrResult(graph, paths));

        AnalysisResults result = analyser.analyser(List.of("TARGET-A", "TARGET-B"), START, END);

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
        when(attributionWorkflow.trace(START, END, null))
                .thenReturn(new CsrResult(graph, Map.of("TARGET", new int[][] {{0}})));

        AnalysisResults result = analyser.analyser(null, START, END);

        assertThat(result.getResults()).containsExactly(
                new AnalysisAdjacencyEntry(target, List.of()));
    }

    @Test
    void keepsProducedAndConsumedNodesDistinctWhenTheirRecordedCostsDiffer() {
        Node produced = node("COMPONENT", 50, null);
        Node consumed = node("COMPONENT", 50, "3500.00");
        Node finished = node("FINISHED", 100, null);
        CsrGraph graph = new CsrGraph(
                new Node[] {produced, consumed, finished},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});
        when(attributionWorkflow.trace(START, END, List.of("FINISHED")))
                .thenReturn(new CsrResult(graph, Map.of("FINISHED", new int[][] {{0, 1, 2}})));

        AnalysisResults result = analyser.analyser(List.of("FINISHED"), START, END);

        assertThat(result.getResults()).containsExactly(
                new AnalysisAdjacencyEntry(produced, List.of(consumed)),
                new AnalysisAdjacencyEntry(consumed, List.of(finished)),
                new AnalysisAdjacencyEntry(finished, List.of()));
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
}
