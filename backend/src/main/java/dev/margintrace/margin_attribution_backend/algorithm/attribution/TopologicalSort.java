package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class implements the topological sort algorithm to build a dependency graph.
 */
public class TopologicalSort {
    public void offsetDependencies() {
        // Implementation of the topological sort algorithm to offset dependencies
    }

    private void caculateOffsets(int[] succ, int[] pred) {
        int[] offset = new int[succ.length + 1];
        int[] outdegree = calculateOutdegree(succ, pred);

        int accumulatedOffset = 0;
        for (int index = 0; index < outdegree.length; index++) {
            offset[index] = accumulatedOffset;
            accumulatedOffset += outdegree[index];
        }
        offset[outdegree.length] = accumulatedOffset;
    }

    /**
     * Calculates each successor node's outdegree in the order defined by {@code succ}.
     *
     * @param succ successor node IDs in downstream-processing order
     * @param pred predecessor node IDs, with one entry per outgoing edge
     * @return outdegrees aligned with {@code succ}, so result[i] belongs to succ[i]
     */
    private int[] calculateOutdegree(int[] succ, int[] pred) {
        Objects.requireNonNull(succ, "succ must not be null");
        Objects.requireNonNull(pred, "pred must not be null");

        Map<Integer, Integer> outdegreeByNode = new HashMap<>();
        for (int predecessor : pred) {
            outdegreeByNode.merge(predecessor, 1, Integer::sum);
        }

        int[] outdegree = new int[succ.length];
        for (int successorIndex = 0; successorIndex < succ.length; successorIndex++) {
            outdegree[successorIndex] = outdegreeByNode.getOrDefault(succ[successorIndex], 0);
        }

        return outdegree;
    }
}
