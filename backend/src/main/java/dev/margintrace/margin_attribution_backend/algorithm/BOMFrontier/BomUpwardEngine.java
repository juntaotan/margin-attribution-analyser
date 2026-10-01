package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

import java.util.Arrays;

/**
 * Finds the upward frontier in an internal DAG with unique terminal nodes.
 * Products become eligible only after every counted child has propagated to them.
 * A selected child stops propagating and therefore blocks shared ancestors.
 * Node values are supplied values, not accumulated here.
 */
public final class BomUpwardEngine {

    /**
     * Traverses from terminals toward products and returns qualifying products in traversal order.
     * The threshold is unsigned, as in OpenCL's {@code cl_ulong}; the absolute value of
     * {@code Long.MIN_VALUE} intentionally retains its high-bit pattern for unsigned comparison.
     * Terminal nodes are traversal seeds and are not tested against the threshold.
     *
     * @param graph raw product-to-material counts and material-to-product reverse edges
     * @param threshold unsigned minimum absolute node value
     * @return selected node indices in traversal order; each eligible node is selected at most once
     */
    public int[] run(BomUpwardGraph graph, long threshold) {
        int nodeCount = graph.nodeValues().length;
        int[] remaining = new int[nodeCount];
        int[] queue = new int[nodeCount];
        int[] results = new int[nodeCount];

        for (int node = 0; node < nodeCount; node++) {
            remaining[node] = graph.offsets()[node + 1] - graph.offsets()[node];
        }

        int queueHead = 0;
        int queueTail = 0;
        for (int terminal : graph.terminalNodes()) {
            queue[queueTail++] = terminal;
        }

        int resultCount = 0;
        while (queueHead < queueTail) {
            int material = queue[queueHead++];
            for (int edge = graph.reverseOffsets()[material];
                    edge < graph.reverseOffsets()[material + 1]; edge++) {
                int product = graph.reverseSuccessors()[edge];
                if (--remaining[product] == 0) {
                    long value = graph.nodeValues()[product];
                    if (graph.nodeComparable()[product] != 0
                            && Long.compareUnsigned(Math.abs(value), threshold) >= 0) {
                        results[resultCount++] = product;
                    } else {
                        queue[queueTail++] = product;
                    }
                }
            }
        }

        return Arrays.copyOf(results, resultCount);
    }
}
