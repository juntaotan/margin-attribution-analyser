package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeSimilarityScorerTests {
    private final NodeSimilarityScorer scorer = new NodeSimilarityScorer();

    @Test
    void returnsOneWhenDirectedEndpointIdsMatch() {
        var edge = scorer.buildEdgeEmbedding(node("M", 1), node("P", 1));
        var candidate = scorer.buildEdgeEmbedding(node("M", 10), node("P", 25));

        assertThat(scorer.scoreEdge(edge, Set.of(candidate))).isOne();
    }

    @Test
    void returnsZeroWhenTheEdgeDirectionIsReversed() {
        var edge = scorer.buildEdgeEmbedding(node("M", 1), node("P", 1));
        var reversed = scorer.buildEdgeEmbedding(node("P", 1), node("M", 1));

        assertThat(scorer.scoreEdge(edge, Set.of(reversed))).isZero();
    }

    private Node node(String id, long quantity) {
        return new Node(id, BigDecimal.valueOf(quantity));
    }
}
