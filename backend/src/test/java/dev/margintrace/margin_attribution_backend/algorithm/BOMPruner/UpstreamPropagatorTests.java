package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamPropagatorTests {
    private final UpstreamPropagator propagator = new UpstreamPropagator();

    @Test
    void outputsDiffForOneWayTree() {
        CsrGraph comparableGraph = new CsrGraph(
                new Node[] {
                        node("Product_1", 1, null),
                        node("Semi-product_1", 1, 80),
                        node("Semi-product_2", 1, 80),
                        node("Material_1", 10, 50),
                        node("Material_2", 10, 50),
                        node("Material_3", 1, 70),
                        node("Material_4", 100, 10)
                },
                new int[] {0, 0, 1, 2, 3, 4, 5, 6},
                new int[] {0, 0, 1, 1, 2, 2}
        );
        CsrGraph actualGraph = new CsrGraph(
                new Node[] {
                        node("Product_1", 1, null),
                        node("Semi-product_1", 1, 120),
                        node("Semi-product_2", 1, 100),
                        node("Material_1", 10, 90),
                        node("Material_2", 10, 50)
                },
                new int[] {0, 0, 1, 2, 3, 4},
                new int[] {0, 0, 1, 1}
        );
        Map<Integer, NodeSimilarityScorer.Embedding> comparablePoints =
                comparablePoints(comparableGraph);

        Map<Integer, List<Integer>> graphDiff = propagator.adaptivePruning(
                actualGraph,
                comparableGraph,
                0,
                comparablePoints,
                BigDecimal.ZERO
        );

        System.out.println("graphDiff = " + graphDiff);
        assertThat(graphDiff).containsExactly(
                Map.entry(1, List.of(0)),
                Map.entry(2, List.of(0))
        );
    }

    private Map<Integer, NodeSimilarityScorer.Embedding> comparablePoints(CsrGraph graph) {
        NodeSimilarityScorer scorer = new NodeSimilarityScorer();
        Map<Integer, NodeSimilarityScorer.Embedding> points = new LinkedHashMap<>();
        for (int position = 0; position < graph.nodes().length; position++) {
            points.put(position, scorer.buildEmbedding(graph.nodes()[position]));
        }
        return points;
    }

    private Node node(String inventoryId, long quantity, Integer cost) {
        return new Node(
                inventoryId,
                BigDecimal.valueOf(quantity),
                cost == null ? null : BigDecimal.valueOf(cost)
        );
    }
}
