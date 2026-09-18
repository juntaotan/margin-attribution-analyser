package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class NodeSimilarityScorerTests {
    private final NodeSimilarityScorer scorer = new NodeSimilarityScorer();

    @Test
    void returnsOneWhenAnyCandidateHasTheSameInventoryId() {
        Node node = new Node("ITEM-1", BigDecimal.ONE);
        Node[] candidates = {
                new Node("ITEM-2", BigDecimal.ONE),
                new Node("ITEM-1", BigDecimal.TEN, BigDecimal.valueOf(25))
        };

        assertThat(scorer.score(node, candidates)).isOne();
    }

    @Test
    void returnsZeroWhenNoCandidateHasTheSameInventoryId() {
        Node node = new Node("ITEM-1", BigDecimal.ONE);
        Node[] candidates = {
                new Node("ITEM-2", BigDecimal.ONE),
                new Node("ITEM-3", BigDecimal.ONE)
        };

        assertThat(scorer.score(node, candidates)).isZero();
    }
}
