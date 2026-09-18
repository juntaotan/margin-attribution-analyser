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
    void traversesFromEToItsUpstreamNodes() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("A"), node("B"), node("C"), node("D"), node("E")},
                new int[] {0, 2, 4, 4, 4, 4},
                new int[] {1, 2, 3, 4}
        );
        Map<Integer, BigDecimal> comparablePoints = Map.of(
                0, BigDecimal.ONE,
                1, BigDecimal.ONE,
                2, BigDecimal.ONE,
                3, BigDecimal.ONE,
                4, BigDecimal.ONE
        );

        assertThat(propagator.adaptivePruning(
                graph,
                4,
                comparablePoints,
                BigDecimal.ZERO
        )).containsExactly(4, 1, 0);
    }

    @Test
    void rejectsAStartPositionOutsideTheGraph() {
        CsrGraph graph = new CsrGraph(
                new Node[] {node("PRODUCT")}, new int[] {0, 0}, new int[] {});

        assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> propagator.adaptivePruning(
                        graph,
                        1,
                        Map.of(0, BigDecimal.ONE),
                        BigDecimal.ZERO
                ));
    }

    private Node node(String inventoryId) {
        return new Node(inventoryId, BigDecimal.ONE);
    }
}
