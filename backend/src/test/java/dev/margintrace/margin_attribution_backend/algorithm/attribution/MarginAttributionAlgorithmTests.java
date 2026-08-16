package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

class MarginAttributionAlgorithmTests {
    private final MarginAttributionAlgorithm algorithm = new MarginAttributionAlgorithm();

    @Test
    void reconstructsEveryCompleteUpstreamPath() {
        CsrGraph graph = graphWithSharedUpstreamPath();

        assertThat(algorithm.reverseTracing(graph, 3)).isDeepEqualTo(new int[][] {
                {0, 2, 3},
                {1, 2, 3}
        });
    }

    @Test
    void returnsTheSourceItselfWhenItHasNoUpstreamNode() {
        CsrGraph graph = graphWithSharedUpstreamPath();

        assertThat(algorithm.reverseTracing(graph, 0)).isDeepEqualTo(new int[][] {{0}});
    }

    @Test
    void rejectsAPositionOutsideTheNodeArray() {
        CsrGraph graph = graphWithSharedUpstreamPath();

        assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> algorithm.reverseTracing(graph, 4))
                .withMessage("position must be between 0 and 3: 4");
    }

    private CsrGraph graphWithSharedUpstreamPath() {
        Node[] nodes = {
                node("MATERIAL-A"),
                node("MATERIAL-B"),
                node("COMPONENT-C"),
                node("PRODUCT-D")
        };

        // MATERIAL-A -> COMPONENT-C -> PRODUCT-D
        // MATERIAL-B -> COMPONENT-C
        return new CsrGraph(
                nodes,
                new int[] {0, 1, 2, 3, 3},
                new int[] {2, 2, 3}
        );
    }

    private Node node(String inventoryId) {
        return new Node(inventoryId, BigDecimal.ONE);
    }
}
