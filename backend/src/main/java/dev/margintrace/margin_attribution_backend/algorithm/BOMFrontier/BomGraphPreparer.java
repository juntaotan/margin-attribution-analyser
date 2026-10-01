package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Prepares raw upward traversal arrays from material-to-product CSR graphs.
 * Inputs describe an internal DAG with unique inventory IDs, and their arrays are
 * immutable by convention. Database costs and thresholds use six decimal places;
 * conversion to long micro-units is exact and must fit the signed long range.
 */
public final class BomGraphPreparer {
    static final int VALUE_SCALE = 6;

    /** The graph and encoded threshold consumed by the upward traversal. */
    public record PreparedPruning(BomUpwardGraph graph, long threshold) {
    }

    /**
     * Aligns costs by inventory ID, selects material-side leaves, and marks their ancestors.
     * Counts include only children reachable from selected leaves. This keeps the complete
     * node index space while preserving the blocking effect of any selected child.
     *
     * @param actual material-to-product graph whose indices appear in the prepared graph
     * @param comparable graph used to compute actual-minus-comparable cost differences
     * @param leafThreshold minimum absolute difference for selecting a material-side leaf
     * @param stopThreshold minimum absolute difference for selecting an ancestor
     * @return traversal arrays and exact long micro-unit stop threshold
     */
    public PreparedPruning prepare(CsrGraph actual, CsrGraph comparable,
            BigDecimal leafThreshold, BigDecimal stopThreshold) {
        Node[] actualNodes = actual.nodes();
        int nodeCount = actualNodes.length;
        Map<String, Node> comparableById = new HashMap<>();
        for (Node node : comparable.nodes()) {
            comparableById.put(node.inventoryId(), node);
        }

        boolean[] hasIncoming = new boolean[nodeCount];
        for (int product : actual.successors()) {
            hasIncoming[product] = true;
        }

        long[] values = new long[nodeCount];
        byte[] comparableFlags = new byte[nodeCount];
        int[] terminals = new int[nodeCount];
        int terminalCount = 0;
        for (int node = 0; node < nodeCount; node++) {
            Node other = comparableById.get(actualNodes[node].inventoryId());
            if (other != null && actualNodes[node].cost() != null && other.cost() != null) {
                BigDecimal difference = actualNodes[node].cost().subtract(other.cost());
                values[node] = microUnits(difference);
                comparableFlags[node] = 1;
                if (!hasIncoming[node] && difference.abs().compareTo(leafThreshold) >= 0) {
                    terminals[terminalCount++] = node;
                }
            }
        }

        boolean[] active = new boolean[nodeCount];
        int[] offsets = new int[nodeCount + 1];
        int[] queue = new int[nodeCount];
        int head = 0;
        int tail = 0;
        for (int terminal = 0; terminal < terminalCount; terminal++) {
            int node = terminals[terminal];
            active[node] = true;
            queue[tail++] = node;
        }
        while (head < tail) {
            int material = queue[head++];
            for (int edge = actual.offset()[material]; edge < actual.offset()[material + 1]; edge++) {
                int product = actual.successors()[edge];
                offsets[product + 1]++;
                if (!active[product]) {
                    active[product] = true;
                    queue[tail++] = product;
                }
            }
        }

        for (int node = 0; node < nodeCount; node++) {
            offsets[node + 1] += offsets[node];
        }

        BomUpwardGraph graph = new BomUpwardGraph(offsets, actual.offset(), actual.successors(),
                Arrays.copyOf(terminals, terminalCount), values, comparableFlags);
        return new PreparedPruning(graph, microUnits(stopThreshold));
    }

    /** Converts a database-scale amount to exact signed long micro-units. */
    private static long microUnits(BigDecimal amount) {
        return amount.movePointRight(VALUE_SCALE).longValueExact();
    }
}
