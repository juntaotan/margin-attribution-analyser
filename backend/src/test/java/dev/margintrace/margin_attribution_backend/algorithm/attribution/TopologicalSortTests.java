package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

class TopologicalSortTests {
    private final TopologicalSort topologicalSort = new TopologicalSort();

    @Test
    void convertsMaterialUsageToCsrGraphInDeterministicNodeOrder() {
        Node materialOne = node("MATERIAL-001", 4);
        Node materialTwo = node("MATERIAL-002", 6);
        Node productOne = node("PRODUCT-001", 10);
        Node productTwo = node("PRODUCT-002", 20);

        Map<Node, List<Node>> materialUsage = new LinkedHashMap<>();
        materialUsage.put(materialTwo, List.of(productTwo));
        materialUsage.put(materialOne, List.of(productOne, productTwo));

        CsrGraph graph = topologicalSort.offsetDependencies(materialUsage);

        assertThat(graph.nodes()).containsExactly(
                materialOne,
                materialTwo,
                productOne,
                productTwo
        );
        assertThat(graph.offset()).containsExactly(0, 2, 3, 3, 3);
        assertThat(graph.successors()).containsExactly(2, 3, 3);
    }

    @Test
    void convertsEmptyMaterialUsageToEmptyCsrGraph() {
        CsrGraph graph = topologicalSort.offsetDependencies(Map.of());

        assertThat(graph.nodes()).isEmpty();
        assertThat(graph.offset()).containsExactly(0);
        assertThat(graph.successors()).isEmpty();
    }

    private Node node(String inventoryId, long quantity) {
        return new Node(inventoryId, BigDecimal.valueOf(quantity));
    }

    @Test
    void preservesEdgesForSameInventoryAndQuantityWithDifferentCosts() {
        Node unknown = new Node("M", BigDecimal.ONE);
        Node zero = new Node("M", BigDecimal.ONE, BigDecimal.ZERO);
        Node paid = new Node("M", BigDecimal.ONE, new BigDecimal("10.00"));
        Node product = node("P", 1);
        CsrGraph graph = topologicalSort.offsetDependencies(Map.of(
                unknown, List.of(product), zero, List.of(product), paid, List.of(product)));

        assertThat(graph.nodes()).containsExactly(unknown, zero, paid, product);
        assertThat(graph.offset()).containsExactly(0, 1, 2, 3, 3);
        assertThat(graph.successors()).containsExactly(3, 3, 3);
        assertThat(paid).isEqualTo(new Node("M", new BigDecimal("1.0"), BigDecimal.TEN));
        assertThat(paid.hashCode()).isEqualTo(
                new Node("M", new BigDecimal("1.0"), BigDecimal.TEN).hashCode());
    }
}
