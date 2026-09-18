package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;

class UpstreamPropagatorTests {
    private final UpstreamPropagator propagator = new UpstreamPropagator();

    @Test
    void returnsNoGraphDiffWhenEveryUpstreamNodeIsWithinVariance() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("C"), node("D"), node("E")},
                new int[] {0, 2, 4, 4, 4, 4},
                new int[] {1, 2, 3, 4}
        );
        CsrGraph comparableGraph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("C"), node("D"), node("E")},
                new int[] {0, 2, 4, 4, 4, 4},
                new int[] {1, 2, 3, 4}
        );
        Map<Integer, NodeSimilarityScorer.Embedding> comparablePoints = Map.of(
                0, new NodeSimilarityScorer.Embedding("A"),
                1, new NodeSimilarityScorer.Embedding("B"),
                2, new NodeSimilarityScorer.Embedding("C"),
                3, new NodeSimilarityScorer.Embedding("D"),
                4, new NodeSimilarityScorer.Embedding("E")
        );

        assertThat(propagator.adaptivePruning(
                graph,
                comparableGraph,
                4,
                comparablePoints,
                BigDecimal.ZERO
        )).isEmpty();
    }

    @Test
    void stopsTheBranchWhenTheParentQuantityDifferenceExceedsVariance() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("A"), new Node("B", BigDecimal.TEN), node("E")},
                new int[] {0, 1, 2, 2},
                new int[] {1, 2}
        );
        CsrGraph comparableGraph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("E")},
                new int[] {0, 1, 2, 2},
                new int[] {1, 2}
        );

        assertThat(propagator.adaptivePruning(
                graph,
                comparableGraph,
                2,
                Map.of(
                        0, new NodeSimilarityScorer.Embedding("A"),
                        1, new NodeSimilarityScorer.Embedding("B"),
                        2, new NodeSimilarityScorer.Embedding("E")
                ),
                BigDecimal.ONE
        )).containsExactly(Map.entry(1, java.util.List.of(2)));
    }

    @Test
    void recordsEveryCompletePathThatReachesTheVarianceBoundary() {
        CsrGraph graph = new CsrGraph(
                new Node[] {
                        new Node("A", BigDecimal.TEN),
                        node("B"),
                        node("C"),
                        node("E")
                },
                new int[] {0, 2, 3, 4, 4},
                new int[] {1, 2, 3, 3}
        );
        CsrGraph comparableGraph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("C"), node("E")},
                new int[] {0, 2, 3, 4, 4},
                new int[] {1, 2, 3, 3}
        );

        assertThat(propagator.adaptivePruning(
                graph,
                comparableGraph,
                3,
                Map.of(
                        0, new NodeSimilarityScorer.Embedding("A"),
                        1, new NodeSimilarityScorer.Embedding("B"),
                        2, new NodeSimilarityScorer.Embedding("C"),
                        3, new NodeSimilarityScorer.Embedding("E")
                ),
                BigDecimal.ONE
        )).containsExactly(
                Map.entry(1, java.util.List.of(3)),
                Map.entry(0, java.util.List.of(1, 2)),
                Map.entry(2, java.util.List.of(3))
        );
    }

    @Test
    void rejectsAStartPositionOutsideTheGraph() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("PRODUCT")}, new int[] {0, 0}, new int[] {});

        assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> propagator.adaptivePruning(
                        graph,
                        graph,
                        1,
                        Map.of(0, new NodeSimilarityScorer.Embedding("PRODUCT")),
                        BigDecimal.ZERO
                ));
    }

    private Node node(String inventoryId) {
        return new Node(inventoryId, BigDecimal.ONE);
    }
}
