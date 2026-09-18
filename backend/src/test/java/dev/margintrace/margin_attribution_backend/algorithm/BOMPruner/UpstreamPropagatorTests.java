package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIndexOutOfBoundsException;

class UpstreamPropagatorTests {
    private final UpstreamPropagator propagator = new UpstreamPropagator();

    @Test
    void traversesFromADownstreamProductToEveryReachableUpstreamNode() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("COMPONENT"), node("PRODUCT")},
                new int[] {0, 1, 2, 3, 3},
                new int[] {2, 2, 3}
        );

        assertThat(propagator.propagate(graph, 3)).containsExactly(3, 2, 0, 1);
    }

    @Test
    void rejectsAStartPositionOutsideTheGraph() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("PRODUCT")}, new int[] {0, 0}, new int[] {});

        assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> propagator.propagate(graph, 1));
    }

    private Node node(String inventoryId) {
        return new Node(inventoryId, BigDecimal.ONE);
    }
}
