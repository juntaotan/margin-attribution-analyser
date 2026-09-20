package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import dev.margintrace.margin_attribution_backend.algorithm.model.ReconciliationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GradBOMReconcilerTests {
    private final GradBOMReconciler reconciler = new GradBOMReconciler();

    @Test
    void scoresDirectedEdgesAndPropagatesEachMaterialBranchUntilCostThreshold() {
        CsrGraph actual = graph(
                new Node[] {node("M1", 20), node("M2", 20), node("M3", 2),
                        node("S", 30), node("P", 40)},
                new int[] {0, 1, 2, 3, 4, 4}, new int[] {3, 3, 4, 4});
        CsrGraph comparable = graph(
                new Node[] {node("P", 30), node("S", 25), node("M3", 1),
                        node("M2", 10), node("M1", 10)},
                new int[] {0, 0, 1, 2, 3, 4}, new int[] {0, 0, 0, 1});

        ReconciliationResult result = reconciler.reconcile(
                actual, comparable, bd(5), bd(8));

        assertThat(result.materialLeafPositions()).containsExactly(0, 1);
        assertThat(result.actualEdgeScores()).containsEntry(new GraphEdge(0, 3, 0), 1)
                .containsEntry(new GraphEdge(1, 3, 1), 0)
                .containsEntry(new GraphEdge(3, 4, 3), 1);
        assertThat(result.comparableEdgeScores()).containsEntry(new GraphEdge(3, 0, 2), 0);
        assertThat(result.paths()).containsExactly(
                new PropagationPath(List.of(0, 3, 4),
                        List.of(new GraphEdge(0, 3, 0), new GraphEdge(3, 4, 3)),
                        PropagationPath.EndReason.THRESHOLD_EXCEEDED, bd(10)),
                new PropagationPath(List.of(1, 3, 4),
                        List.of(new GraphEdge(1, 3, 1), new GraphEdge(3, 4, 3)),
                        PropagationPath.EndReason.THRESHOLD_EXCEEDED, bd(10)));
    }

    @Test
    void recordsEveryBranchAndGraphEndWhenStopThresholdIsNotExceeded() {
        CsrGraph actual = graph(new Node[] {node("M", 10), node("A", 10),
                        node("B", 10)}, new int[] {0, 2, 2, 2}, new int[] {1, 2});
        CsrGraph comparable = graph(new Node[] {node("M", 0), node("A", 5),
                        node("B", 5)}, new int[] {0, 2, 2, 2}, new int[] {1, 2});

        ReconciliationResult result = reconciler.reconcile(actual, comparable, bd(10), bd(5));

        assertThat(result.materialLeafPositions()).containsExactly(0);
        assertThat(result.paths()).containsExactly(
                new PropagationPath(List.of(0, 1), List.of(new GraphEdge(0, 1, 0)),
                        PropagationPath.EndReason.GRAPH_END, bd(5)),
                new PropagationPath(List.of(0, 2), List.of(new GraphEdge(0, 2, 1)),
                        PropagationPath.EndReason.GRAPH_END, bd(5)));
    }

    @Test
    void skipsLeafWithUnavailableCost() {
        CsrGraph actual = graph(new Node[] {new Node("M", BigDecimal.ONE)},
                new int[] {0, 0}, new int[] {});
        CsrGraph comparable = graph(new Node[] {node("M", 1)},
                new int[] {0, 0}, new int[] {});

        ReconciliationResult result = reconciler.reconcile(actual, comparable, bd(0), bd(0));

        assertThat(result.materialLeafPositions()).isEmpty();
        assertThat(result.paths()).isEmpty();
    }

    @Test
    void continuesAcrossMatchedNodeWithUnavailableCost() {
        CsrGraph actual = graph(new Node[] {node("M", 10),
                        new Node("S", BigDecimal.ONE), node("P", 20)},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});
        CsrGraph comparable = graph(new Node[] {node("M", 0),
                        new Node("S", BigDecimal.ONE), node("P", 5)},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});

        ReconciliationResult result = reconciler.reconcile(actual, comparable, bd(10), bd(10));

        assertThat(result.paths()).containsExactly(new PropagationPath(
                List.of(0, 1, 2), List.of(new GraphEdge(0, 1, 0), new GraphEdge(1, 2, 1)),
                PropagationPath.EndReason.THRESHOLD_EXCEEDED, bd(15)));
    }

    @Test
    void unmatchedIntermediateNodeDoesNotStopCostOnlyPropagation() {
        CsrGraph actual = graph(new Node[] {
                        new Node("M", bd(100), bd(10)),
                        new Node("ONLY-ACTUAL", bd(100), bd(100)),
                        new Node("P", bd(100), bd(20))},
                new int[] {0, 1, 2, 2}, new int[] {1, 2});
        CsrGraph comparable = graph(new Node[] {
                        new Node("P", bd(1), bd(5)),
                        new Node("M", bd(1), bd(0))},
                new int[] {0, 0, 0}, new int[] {});

        ReconciliationResult result = reconciler.reconcile(actual, comparable, bd(10), bd(10));

        assertThat(result.paths()).containsExactly(new PropagationPath(
                List.of(0, 1, 2), List.of(new GraphEdge(0, 1, 0), new GraphEdge(1, 2, 1)),
                PropagationPath.EndReason.THRESHOLD_EXCEEDED, bd(15)));
    }

    @Test
    void scoresAndRecordsParallelEdgesSeparately() {
        CsrGraph actual = graph(new Node[] {node("M", 10), node("P", 10)},
                new int[] {0, 2, 2}, new int[] {1, 1});
        CsrGraph comparable = graph(new Node[] {node("M", 0), node("P", 10)},
                new int[] {0, 1, 1}, new int[] {1});

        ReconciliationResult result = reconciler.reconcile(actual, comparable, bd(10), bd(5));

        assertThat(result.actualEdgeScores()).containsOnly(
                Map.entry(new GraphEdge(0, 1, 0), 1),
                Map.entry(new GraphEdge(0, 1, 1), 1));
        assertThat(result.paths()).hasSize(2);
        assertThat(result.paths().get(0).edges()).containsExactly(new GraphEdge(0, 1, 0));
        assertThat(result.paths().get(1).edges()).containsExactly(new GraphEdge(0, 1, 1));
    }

    private CsrGraph graph(Node[] nodes, int[] offsets, int[] successors) {
        return new CsrGraph(nodes, offsets, successors);
    }

    private Node node(String id, long cost) {
        return new Node(id, BigDecimal.ONE, bd(cost));
    }

    private BigDecimal bd(long value) {
        return BigDecimal.valueOf(value);
    }
}
