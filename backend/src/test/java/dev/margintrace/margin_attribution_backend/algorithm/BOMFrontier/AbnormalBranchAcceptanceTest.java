package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

import static org.junit.jupiter.api.Assertions.*;

import dev.margintrace.margin_attribution_backend.algorithm.model.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AbnormalBranchAcceptanceTest {
    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static CsrGraph graph(String[] ids, String[] costs, int[] offsets, int[] cols) {
        Node[] nodes = new Node[ids.length];
        for (int i = 0; i < ids.length; i++)
            nodes[i] = new Node(ids[i], BigDecimal.ONE, costs[i] == null ? null : d(costs[i]));
        return new CsrGraph(nodes, offsets, cols);
    }

    static CsrGraph normal() {
        return graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"180", "100", "80", "100", "80"},
                new int[] {0, 0, 1, 2, 3, 4},
                new int[] {0, 0, 1, 2});
    }

    static CsrGraph changed() {
        return graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"200", "120", "80", "120", "80"},
                normal().offset(),
                normal().successors());
    }

    static List<PropagationPath> paths(CsrGraph actual) {
        return new BomFrontierReconciler().reconcile(actual, normal(), d("10"), d("15"));
    }

    static List<String> decode(CsrGraph g, PropagationPath p) {
        return p.positions().stream().map(i -> g.nodes()[i].inventoryId()).toList();
    }

    @Test
    void B01_B02_normalAndFixedChange() {
        assertTrue(paths(normal()).isEmpty());
        var paths = paths(changed());
        assertEquals(1, paths.size());
        assertEquals(List.of(3, 1), paths.getFirst().positions());
        assertEquals(List.of("D", "B"), decode(changed(), paths.getFirst()));
        assertEquals(List.of(new GraphEdge(3, 1, 2)), paths.getFirst().edges());
        assertEquals(0, d("20").compareTo(paths.getFirst().endingCostDifference()));
    }

    @Test
    void B03_insertedNoncomparableIntermediate() {
        var g = graph(
                new String[] {"A", "B", "C", "D", "E", "X"},
                new String[] {"200", "120", "80", "120", "80", "120"},
                new int[] {0, 0, 1, 2, 3, 4, 5},
                new int[] {0, 0, 5, 2, 1});
        var p = paths(g);
        assertEquals(1, p.size());
        assertEquals(List.of("D", "X", "B"), decode(g, p.getFirst()));
        assertEquals(List.of(3, 5, 1), p.getFirst().positions());
        assertEquals(
                List.of(new GraphEdge(3, 5, 2), new GraphEdge(5, 1, 4)),
                p.getFirst().edges());
        assertEquals(
                0,
                new BomGraphPreparer()
                        .prepare(g, normal(), d("10"), d("15"))
                        .graph()
                        .nodeComparable()[5]);
    }

    @Test
    void B04_movedLeafUsesActualEdgesAndExplicitCosts() {
        var g = graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"200", "100", "100", "120", "80"},
                normal().offset(),
                new int[] {0, 0, 2, 2});
        var p = paths(g);
        assertEquals(1, p.size());
        assertEquals(List.of("D", "C"), decode(g, p.getFirst()));
        assertEquals(List.of(new GraphEdge(3, 2, 2)), p.getFirst().edges());
    }

    @Test
    void B05_B06_inclusiveExactNegativeAndAncestorPropagation() {
        for (String amount : List.of("110", "90")) {
            var g = graph(
                    new String[] {"A", "B", "C", "D", "E"},
                    new String[] {"200", "120", "80", amount, "80"},
                    normal().offset(),
                    normal().successors());
            assertEquals(List.of("D", "B"), decode(g, paths(g).getFirst()));
        }
        var below = graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"200", "120", "80", "109.999999", "80"},
                normal().offset(),
                normal().successors());
        assertTrue(paths(below).isEmpty());
        var through = graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"200", "105", "80", "120", "80"},
                normal().offset(),
                normal().successors());
        assertEquals(List.of("D", "B", "A"), decode(through, paths(through).getFirst()));
    }

    @Test
    void B07_selectedChildBlocksSharedAncestor() {
        var p = paths(changed());
        assertEquals(1, p.size());
        assertFalse(p.getFirst().positions().contains(0));
    }

    @Test
    void B08_B09_missingReplacementAndPureStructureDoNotInventSeeds() {
        var missing = graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"200", "120", "80", null, "80"},
                normal().offset(),
                normal().successors());
        assertTrue(paths(missing).isEmpty());
        var only = graph(
                new String[] {"A", "B", "C", "F", "E"},
                new String[] {"200", "120", "80", "120", "80"},
                normal().offset(),
                normal().successors());
        assertTrue(paths(only).isEmpty());
        var moved = graph(
                new String[] {"A", "B", "C", "D", "E"},
                new String[] {"180", "100", "80", "100", "80"},
                normal().offset(),
                new int[] {0, 0, 2, 2});
        assertTrue(paths(moved).isEmpty());
    }

    @Test
    void B10_invalidThresholdsCsrCyclesAndReindexing() {
        for (String threshold : List.of("-1", "0.0000001", "9223372036854.775808"))
            assertThrows(IllegalArgumentException.class, () -> new BomGraphPreparer()
                    .prepare(normal(), normal(), d(threshold), d("1")));
        var cycle = graph(new String[] {"A", "B"}, new String[] {"1", "1"}, new int[] {0, 1, 2}, new int[] {1, 0});
        assertThrows(IllegalArgumentException.class, () -> paths(cycle));
        assertThrows(
                IllegalArgumentException.class,
                () -> paths(new CsrGraph(normal().nodes(), new int[] {0}, new int[] {})));
        var g = graph(
                new String[] {"D", "B", "A", "E", "C"},
                new String[] {"120", "120", "200", "80", "80"},
                new int[] {0, 1, 2, 2, 3, 4},
                new int[] {1, 2, 4, 2});
        assertEquals(List.of("D", "B"), decode(g, paths(g).getFirst()));
    }
}
