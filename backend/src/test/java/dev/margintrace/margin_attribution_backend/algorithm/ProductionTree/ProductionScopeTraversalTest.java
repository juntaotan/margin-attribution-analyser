package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFixtures.graph;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionScopeTraversalTest {
    final ProductionScopeTraversal traversal = new ProductionScopeTraversal();
    final ActualGraph supplied = graph("fixture", Map.of("P", "A", "Q", "X"), "A>S", "A>R", "S>R", "X>R");

    /** Consistently reindex nodes, reverse edge direction and reverse successor order. */
    static ActualGraph permute(ActualGraph g, Direction direction) {
        int n = g.nodes().size();
        List<GraphNode> nodes = new ArrayList<>(g.nodes());
        Collections.reverse(nodes);
        Map<Integer, List<Map.Entry<Integer, String>>> rows = new HashMap<>();
        for (int i = 0; i < n; i++)
            for (int k = g.rowOffsets()[i]; k < g.rowOffsets()[i + 1]; k++) {
                int a = n - 1 - i, b = n - 1 - g.colIndices()[k];
                if (direction != g.direction()) {
                    int swap = a;
                    a = b;
                    b = swap;
                }
                rows.computeIfAbsent(a, x -> new ArrayList<>())
                        .add(Map.entry(b, g.edgeIds().get(k)));
            }
        int[] offsets = new int[n + 1];
        List<Integer> cols = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            offsets[i] = cols.size();
            List<Map.Entry<Integer, String>> row = rows.getOrDefault(i, new ArrayList<>());
            Collections.reverse(row);
            for (var edge : row) {
                cols.add(edge.getKey());
                ids.add(edge.getValue());
            }
        }
        offsets[n] = cols.size();
        return new ActualGraph(
                g.graphId(),
                g.namespace(),
                direction,
                g.verifiedActual(),
                nodes,
                offsets,
                cols.stream().mapToInt(Integer::intValue).toArray(),
                ids,
                g.roots(),
                g.completeCoverage(),
                g.coverageStart(),
                g.coverageEnd());
    }

    @Test
    void bothDirectionsMultipleRootsAndEveryDirectDeepPath() {
        TopDownScope scope = traversal.traverse(supplied, "fixture", "P", "A");
        assertEquals(
                Set.of("A", "R", "S"),
                new HashSet<>(
                        scope.permittedNodes().stream().map(GraphNode::nodeId).toList()));
        assertEquals(List.of(List.of("A", "R"), List.of("A", "S", "R")), scope.paths());
        assertEquals(List.of("R"), scope.terminalNodeIds());
        assertEquals(List.of("R", "S", "A"), scope.bottomUpNodeIds());
        assertEquals(3, scope.permittedEdges().size());
        assertEquals(
                List.of(List.of("X", "R")),
                traversal.traverse(supplied, "fixture", "Q", "X").paths());
        for (Direction d : Direction.values()) {
            TopDownScope other = traversal.traverse(permute(supplied, d), "fixture", "P", "A");
            assertEquals(scope.paths(), other.paths());
            assertEquals(scope.permittedEdges(), other.permittedEdges());
            assertEquals(scope.bottomUpNodeIds(), other.bottomUpNodeIds());
        }
    }

    @Test
    void missingInputRootNamespaceActualVerificationAndCoverage() {
        assertEquals(
                "MISSING_PRECEDING_ACTUAL_GRAPH",
                assertThrows(IllegalArgumentException.class, () -> traversal.traverse(null, "fixture", "P", "A"))
                        .getMessage());
        assertThrows(IllegalArgumentException.class, () -> traversal.traverse(supplied, "wrong", "P", "A"));
        assertThrows(IllegalArgumentException.class, () -> traversal.traverse(supplied, "fixture", "absent", "A"));
        assertThrows(IllegalArgumentException.class, () -> traversal.traverse(supplied, "fixture", "P", "OTHER"));
        ActualGraph incomplete = new ActualGraph(
                supplied.graphId(),
                "fixture",
                supplied.direction(),
                true,
                supplied.nodes(),
                supplied.rowOffsets(),
                supplied.colIndices(),
                supplied.edgeIds(),
                supplied.roots(),
                false,
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 31));
        assertTrue(
                traversal.traverse(incomplete, "fixture", "P", "A").statuses().contains("INCOMPLETE_GRAPH_COVERAGE"));
        ActualGraph bom = new ActualGraph(
                supplied.graphId(),
                "fixture",
                supplied.direction(),
                false,
                supplied.nodes(),
                supplied.rowOffsets(),
                supplied.colIndices(),
                supplied.edgeIds(),
                supplied.roots(),
                true,
                null,
                null);
        assertThrows(IllegalArgumentException.class, () -> traversal.traverse(bom, "fixture", "P", "A"));
    }

    @Test
    void malformedArraysAndStableIdentitiesFailBeforeTraversal() {
        List<ActualGraph> malformed = List.of(
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        supplied.nodes(),
                        new int[] {0},
                        new int[] {},
                        List.of(),
                        supplied.roots(),
                        true,
                        null,
                        null),
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        List.of(new GraphNode("A", "A")),
                        new int[] {0, 1},
                        new int[] {9},
                        List.of("e"),
                        Map.of("P", "A"),
                        true,
                        null,
                        null),
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        List.of(new GraphNode("A", "A")),
                        new int[] {0, 2},
                        new int[] {0, 0},
                        List.of("e", "f"),
                        Map.of("P", "A"),
                        true,
                        null,
                        null),
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        List.of(new GraphNode("A", "A"), new GraphNode("A", "R")),
                        new int[] {0, 0, 0},
                        new int[] {},
                        List.of(),
                        Map.of("P", "A"),
                        true,
                        null,
                        null),
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        supplied.nodes(),
                        new int[] {0, 2, 1, 3, 4},
                        supplied.colIndices(),
                        supplied.edgeIds(),
                        supplied.roots(),
                        true,
                        null,
                        null),
                new ActualGraph(
                        "g",
                        "fixture",
                        Direction.PRODUCT_TO_MATERIAL,
                        true,
                        supplied.nodes(),
                        supplied.rowOffsets(),
                        supplied.colIndices(),
                        List.of("e", "e", "f", "g"),
                        supplied.roots(),
                        true,
                        null,
                        null));
        for (ActualGraph g : malformed)
            assertThrows(IllegalArgumentException.class, () -> traversal.traverse(g, "fixture", "P", "A"));
    }

    @Test
    void cyclesAndSelfConsumptionNeverClaimCompleteTerminals() {
        TopDownScope cycle = traversal.traverse(graph("fixture", Map.of("P", "A"), "A>S", "S>A"), "fixture", "P", "A");
        assertTrue(cycle.statuses().contains("CYCLE"));
        assertTrue(cycle.terminalNodeIds().isEmpty());
        TopDownScope self = traversal.traverse(graph("fixture", Map.of("P", "A"), "A>A"), "fixture", "P", "A");
        assertTrue(self.statuses().containsAll(Set.of("CYCLE", "SELF_CONSUMPTION")));
    }
}
