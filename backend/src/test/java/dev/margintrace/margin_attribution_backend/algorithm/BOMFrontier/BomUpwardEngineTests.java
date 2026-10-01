package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests upward frontier propagation and graph preparation, adapted from OpenCL
 * {@code bom_upward_engine_test.cpp} where their semantics apply.
 */
class BomUpwardEngineTests {
    private final BomUpwardEngine engine = new BomUpwardEngine();
    private final BomGraphPreparer preparer = new BomGraphPreparer();

    @Test
    void propagatesUntilAnAncestorMeetsTheThreshold() {
        BomUpwardGraph graph = raw(new long[]{0, 0, 500, 1_500}, flags(4),
                new int[]{0, 1}, edges(0, 2, 1, 2, 2, 3));
        assertArrayEquals(new int[]{3}, engine.run(graph, 1_000));
    }

    @Test
    void positiveAndNegativeValuesUseAnInclusiveBoundary() {
        BomUpwardGraph graph = raw(new long[]{0, 999, 1_000, 1_001, -999, -1_000, -1_001},
                flags(7), new int[]{0}, edges(0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6));
        assertSorted(new int[]{2, 3, 5, 6}, engine.run(graph, 1_000));
    }

    @Test
    void zeroThresholdSelectsComparableZeroButNeverTestsTerminals() {
        BomUpwardGraph graph = raw(new long[]{Long.MAX_VALUE, 0}, flags(2),
                new int[]{0}, edges(0, 1));
        assertArrayEquals(new int[]{1}, engine.run(graph, 0));
        assertArrayEquals(new int[0], engine.run(raw(new long[]{Long.MAX_VALUE},
                flags(1), new int[]{0}, edges()), 0));
    }

    @Test
    void noncomparableNodesPropagateAndSelectedNodesStop() {
        BomUpwardGraph chain = raw(new long[]{0, 5_000, 2_000},
                new byte[]{1, 0, 1}, new int[]{0}, edges(0, 1, 1, 2));
        assertArrayEquals(new int[]{2}, engine.run(chain, 1_000));

        BomUpwardGraph stopped = raw(new long[]{0, 2_000, 3_000}, flags(3),
                new int[]{0}, edges(0, 1, 1, 2));
        assertArrayEquals(new int[]{1}, engine.run(stopped, 1_000));
    }

    @Test
    void sharedParentsWaitForAllChildrenAndDiamondResultAppearsOnce() {
        BomUpwardGraph graph = raw(new long[]{0, 0, 100, 1_500}, flags(4),
                new int[]{0}, edges(0, 1, 0, 2, 1, 3, 2, 3));
        assertArrayEquals(new int[]{3}, engine.run(graph, 1_000));

        BomUpwardGraph twoTerminals = raw(new long[]{0, 0, 500, 1_500}, flags(4),
                new int[]{0, 1}, edges(0, 2, 1, 2, 2, 3));
        assertArrayEquals(new int[]{3}, engine.run(twoTerminals, 1_000));
    }

    @Test
    void delayedChildMustPropagateBeforeItsParentIsSelected() {
        BomUpwardGraph graph = raw(new long[]{0, 0, 2_000, 500}, flags(4),
                new int[]{0, 1}, edges(0, 2, 1, 3, 3, 2));
        assertArrayEquals(new int[]{2}, engine.run(graph, 1_000));
    }

    @Test
    void selectedOrUnseededChildBlocksItsAncestor() {
        BomUpwardGraph selectedChild = raw(new long[]{0, 2_000, 500, 3_000}, flags(4),
                new int[]{0}, edges(0, 1, 0, 2, 1, 3, 2, 3));
        assertArrayEquals(new int[]{1}, engine.run(selectedChild, 1_000));

        BomUpwardGraph missingTerminal = raw(new long[]{0, 0, 2_000}, flags(3),
                new int[]{0}, edges(0, 2, 1, 2));
        assertArrayEquals(new int[0], engine.run(missingTerminal, 1_000));
    }

    @Test
    void disconnectedAndEmptyFrontiersStayDisconnected() {
        BomUpwardGraph disconnected = raw(new long[]{0, 2_000, 0, 3_000}, flags(4),
                new int[]{0}, edges(0, 1, 2, 3));
        assertArrayEquals(new int[]{1}, engine.run(disconnected, 1_000));
        assertArrayEquals(new int[0], engine.run(raw(new long[0], new byte[0],
                new int[0], edges()), 0));
        assertArrayEquals(new int[0], engine.run(raw(new long[]{0, 2_000},
                flags(2), new int[0], edges(0, 1)), 1_000));
        assertArrayEquals(new int[0], engine.run(raw(new long[]{0, 2_000},
                new byte[2], new int[]{0}, edges(0, 1)), 0));
    }

    @Test
    void repeatedCallsDoNotChangeTheGraphOrEngineState() {
        BomUpwardGraph graph = raw(new long[]{0, 2_000, 3_000}, flags(3),
                new int[]{0}, edges(0, 1, 1, 2));
        int[] offsets = graph.offsets().clone();
        int[] reverseOffsets = graph.reverseOffsets().clone();
        int[] successors = graph.reverseSuccessors().clone();
        int[] terminals = graph.terminalNodes().clone();
        long[] values = graph.nodeValues().clone();
        byte[] flags = graph.nodeComparable().clone();

        assertArrayEquals(new int[]{1}, engine.run(graph, 1_000));
        assertArrayEquals(new int[]{2}, engine.run(graph, 2_500));
        assertArrayEquals(new int[]{1}, engine.run(graph, 1_000));
        assertArrayEquals(offsets, graph.offsets());
        assertArrayEquals(reverseOffsets, graph.reverseOffsets());
        assertArrayEquals(successors, graph.reverseSuccessors());
        assertArrayEquals(terminals, graph.terminalNodes());
        assertArrayEquals(values, graph.nodeValues());
        assertArrayEquals(flags, graph.nodeComparable());
    }

    @Test
    void unsignedThresholdIncludesMinValueAbsolutePattern() {
        BomUpwardGraph graph = raw(new long[]{0, Long.MIN_VALUE, Long.MAX_VALUE, -1_001},
                flags(4), new int[]{0}, edges(0, 1, 0, 2, 0, 3));
        assertArrayEquals(new int[]{1}, engine.run(graph, Long.MIN_VALUE));
        assertArrayEquals(new int[0], engine.run(graph, -1L));
        assertSorted(new int[]{1, 2, 3}, engine.run(graph, 1_000));
    }

    @Test
    void handlesLongChainAndFanOut() {
        int[] chainEdges = new int[2 * 127];
        for (int node = 0; node < 127; node++) {
            chainEdges[2 * node] = node;
            chainEdges[2 * node + 1] = node + 1;
        }
        long[] values = new long[128];
        values[127] = 2_000;
        assertArrayEquals(new int[]{127}, engine.run(raw(values, flags(128),
                new int[]{0}, chainEdges), 1_000));

        assertSorted(new int[]{1, 2, 3, 4}, engine.run(raw(
                new long[]{0, 1_000, 1_001, 1_002, 1_003}, flags(5),
                new int[]{0}, edges(0, 1, 0, 2, 0, 3, 0, 4)), 1_000));
    }

    @Test
    void manyLeavesConvergeOnEveryParentExactlyOnce() {
        int leaves = 128;
        int parents = 16;
        int[] pairs = new int[2 * leaves * parents];
        int edge = 0;
        for (int child = 0; child < leaves; child++) {
            for (int parent = leaves; parent < leaves + parents; parent++) {
                pairs[edge++] = child;
                pairs[edge++] = parent;
            }
        }
        long[] values = new long[leaves + parents];
        Arrays.fill(values, 1_500);
        int[] terminals = new int[leaves];
        int[] expected = new int[parents];
        for (int node = 0; node < leaves; node++) {
            terminals[node] = node;
        }
        for (int node = 0; node < parents; node++) {
            expected[node] = leaves + node;
        }
        assertSorted(expected, engine.run(raw(values, flags(values.length), terminals, pairs), 1_000));
    }

    @Test
    void seededRandomDagsMatchIndependentChildStateScanner() {
        int seed = 20261001;
        Random random = new Random(seed);
        for (int sample = 0; sample < 100; sample++) {
            int nodeCount = 2 + random.nextInt(47);
            List<Integer> order = new ArrayList<>();
            for (int node = 0; node < nodeCount; node++) {
                order.add(node);
            }
            Collections.shuffle(order, random);
            long[] values = new long[nodeCount];
            byte[] comparable = new byte[nodeCount];
            for (int node = 0; node < nodeCount; node++) {
                values[node] = random.nextInt(4_001) - 2_000;
                comparable[node] = (byte) (random.nextInt(3) == 0 ? 0 : 1);
            }
            List<Integer> edgeList = new ArrayList<>();
            edgeList.add(order.get(0));
            edgeList.add(order.get(1));
            for (int child = 0; child < nodeCount; child++) {
                for (int parent = child + 1; parent < nodeCount; parent++) {
                    if (child != 0 || parent != 1) {
                        if (random.nextInt(5) == 0) {
                            edgeList.add(order.get(child));
                            edgeList.add(order.get(parent));
                        }
                    }
                }
            }
            int[] pairs = edgeList.stream().mapToInt(Integer::intValue).toArray();
            int[] terminals = terminalsFromPairs(nodeCount, pairs);
            shuffle(terminals, random);
            BomUpwardGraph graph = raw(values, comparable, terminals, pairs);
            for (long threshold : new long[]{0, 1_000, 2_001}) {
                int[] expected = scanChildren(values, comparable, pairs, threshold);
                int[] actual = engine.run(graph, threshold);
                Arrays.sort(expected);
                Arrays.sort(actual);
                assertArrayEquals(expected, actual,
                        "seed=" + seed + " sample=" + sample + " threshold=" + threshold);
            }
        }
    }

    @Test
    void preparerAlignsReorderedIdsAndUsesExactFractionalUnits() {
        CsrGraph actual = csr(new Node[]{node("M", "0.000002"), node("P", "1.000000")},
                edges(0, 1));
        CsrGraph comparable = csr(new Node[]{node("P", "1.000001"),
                node("M", "0.000001")}, edges());
        BomGraphPreparer.PreparedPruning prepared = preparer.prepare(actual, comparable,
                decimal("0.000001"), decimal("0.000001"));
        assertArrayEquals(new int[]{0}, prepared.graph().terminalNodes());
        assertArrayEquals(new long[]{1, -1}, prepared.graph().nodeValues());
        assertArrayEquals(new byte[]{1, 1}, prepared.graph().nodeComparable());
        assertEquals(1L, prepared.threshold());
        assertArrayEquals(new int[]{1}, engine.run(prepared.graph(), prepared.threshold()));
    }

    @Test
    void preparerLetsMissingOrUnavailableCostsPropagate() {
        CsrGraph actual = csr(new Node[]{node("M", "5"), new Node("A", decimal("1")),
                node("B", "8"), node("C", "9"), node("S", "10")},
                edges(0, 1, 1, 2, 2, 3, 3, 4));
        CsrGraph comparable = csr(new Node[]{node("S", "7"), node("M", "0"),
                node("A", "1"), new Node("B", decimal("1"))}, edges());
        BomGraphPreparer.PreparedPruning prepared = preparer.prepare(actual, comparable,
                decimal("1"), decimal("2"));
        assertArrayEquals(new byte[]{1, 0, 0, 0, 1}, prepared.graph().nodeComparable());
        assertArrayEquals(new int[]{4}, engine.run(prepared.graph(), prepared.threshold()));
    }

    @Test
    void preparerCountsOnlyChildrenReachableFromSelectedLeaves() {
        CsrGraph actual = csr(new Node[]{node("M1", "2"), node("M2", "3"),
                node("M3", "0"), node("S", "0"), node("P", "10")},
                edges(0, 3, 1, 3, 2, 3, 3, 4));
        CsrGraph comparable = csr(new Node[]{node("M1", "0"), node("M2", "0"),
                node("M3", "0"), node("S", "0"), node("P", "0")}, edges());
        BomGraphPreparer.PreparedPruning prepared = preparer.prepare(actual, comparable,
                decimal("1"), decimal("5"));
        assertArrayEquals(new int[]{0, 1}, prepared.graph().terminalNodes());
        assertArrayEquals(new int[]{0, 0, 0, 0, 2, 3}, prepared.graph().offsets());
        assertArrayEquals(new int[]{4}, engine.run(prepared.graph(), prepared.threshold()));
    }

    @Test
    void preparerWithNoEligibleLeavesReturnsAnEmptyFrontier() {
        CsrGraph actual = csr(new Node[]{node("M", "0"), node("P", "10")}, edges(0, 1));
        CsrGraph comparable = csr(new Node[]{node("M", "0"), node("P", "0")}, edges());
        BomGraphPreparer.PreparedPruning prepared = preparer.prepare(actual, comparable,
                decimal("1"), decimal("1"));
        assertArrayEquals(new int[0], prepared.graph().terminalNodes());
        assertArrayEquals(new int[]{0, 0, 0}, prepared.graph().offsets());
        assertArrayEquals(new int[0], engine.run(prepared.graph(), prepared.threshold()));
    }

    @Test
    void reconcilerReconstructsEverySelectedLeafPathToTheBoundary() {
        CsrGraph actual = csr(new Node[]{node("M1", "2"), node("M2", "3"),
                node("S", "0"), node("P", "10")}, edges(0, 2, 1, 2, 2, 3));
        CsrGraph comparable = csr(new Node[]{node("P", "0"), node("S", "0"),
                node("M2", "0"), node("M1", "0")}, edges());

        List<PropagationPath> paths = new BomFrontierReconciler().reconcile(
                actual, comparable, decimal("1"), decimal("5"));

        assertEquals(List.of(
                new PropagationPath(List.of(0, 2, 3),
                        List.of(new GraphEdge(0, 2, 0), new GraphEdge(2, 3, 2)),
                        PropagationPath.EndReason.THRESHOLD_EXCEEDED, decimal("10.000000")),
                new PropagationPath(List.of(1, 2, 3),
                        List.of(new GraphEdge(1, 2, 1), new GraphEdge(2, 3, 2)),
                        PropagationPath.EndReason.THRESHOLD_EXCEEDED, decimal("10.000000"))), paths);
    }

    /** Builds raw engine arrays from material-to-product edges without changing result multiplicity. */
    private static BomUpwardGraph raw(long[] values, byte[] flags, int[] terminals, int[] pairs) {
        int nodeCount = values.length;
        int[] reverseOffsets = new int[nodeCount + 1];
        int[] offsets = new int[nodeCount + 1];
        for (int edge = 0; edge < pairs.length; edge += 2) {
            reverseOffsets[pairs[edge] + 1]++;
            offsets[pairs[edge + 1] + 1]++;
        }
        for (int node = 0; node < nodeCount; node++) {
            reverseOffsets[node + 1] += reverseOffsets[node];
            offsets[node + 1] += offsets[node];
        }
        int[] cursors = reverseOffsets.clone();
        int[] successors = new int[pairs.length / 2];
        for (int edge = 0; edge < pairs.length; edge += 2) {
            successors[cursors[pairs[edge]]++] = pairs[edge + 1];
        }
        return new BomUpwardGraph(offsets, reverseOffsets, successors, terminals, values, flags);
    }

    /** Builds the shared CSR model with material-to-product edge orientation. */
    private static CsrGraph csr(Node[] nodes, int[] pairs) {
        BomUpwardGraph graph = raw(new long[nodes.length], new byte[nodes.length],
                new int[0], pairs);
        return new CsrGraph(nodes, graph.reverseOffsets(), graph.reverseSuccessors());
    }

    private static Node node(String id, String cost) {
        return new Node(id, BigDecimal.ONE, decimal(cost));
    }

    private static BigDecimal decimal(String amount) {
        return new BigDecimal(amount);
    }

    private static byte[] flags(int count) {
        byte[] flags = new byte[count];
        Arrays.fill(flags, (byte) 1);
        return flags;
    }

    private static int[] edges(int... pairs) {
        return pairs;
    }

    /** Sorts arrays directly so repeated results remain visible to the assertion. */
    private static void assertSorted(int[] expected, int[] actual) {
        Arrays.sort(expected);
        Arrays.sort(actual);
        assertArrayEquals(expected, actual);
    }

    /** Finds terminal nodes directly from the original edge pairs. */
    private static int[] terminalsFromPairs(int nodeCount, int[] pairs) {
        boolean[] hasChild = new boolean[nodeCount];
        for (int edge = 1; edge < pairs.length; edge += 2) {
            hasChild[pairs[edge]] = true;
        }
        int[] terminals = new int[nodeCount];
        int count = 0;
        for (int node = 0; node < nodeCount; node++) {
            if (!hasChild[node]) {
                terminals[count++] = node;
            }
        }
        return Arrays.copyOf(terminals, count);
    }

    /** Repeatedly scans original edges using child states, independent of engine counters. */
    private static int[] scanChildren(long[] values, byte[] comparable, int[] pairs, long threshold) {
        int[] state = new int[values.length]; // 0 unresolved, 1 propagated, 2 selected
        int[] terminals = terminalsFromPairs(values.length, pairs);
        for (int terminal : terminals) {
            state[terminal] = 1;
        }
        int[] results = new int[values.length];
        int resultCount = 0;
        boolean changed;
        do {
            changed = false;
            for (int node = 0; node < values.length; node++) {
                if (state[node] != 0) {
                    continue;
                }
                boolean ready = true;
                for (int edge = 0; edge < pairs.length; edge += 2) {
                    if (pairs[edge + 1] == node && state[pairs[edge]] != 1) {
                        ready = false;
                        break;
                    }
                }
                if (ready) {
                    long value = values[node];
                    long magnitude = value < 0 ? -(value + 1) + 1 : value;
                    boolean selected = comparable[node] != 0
                            && Long.compareUnsigned(magnitude, threshold) >= 0;
                    state[node] = selected ? 2 : 1;
                    if (selected) {
                        results[resultCount++] = node;
                    }
                    changed = true;
                }
            }
        } while (changed);
        return Arrays.copyOf(results, resultCount);
    }

    private static void shuffle(int[] values, Random random) {
        for (int last = values.length - 1; last > 0; last--) {
            int other = random.nextInt(last + 1);
            int held = values[last];
            values[last] = values[other];
            values[other] = held;
        }
    }
}
