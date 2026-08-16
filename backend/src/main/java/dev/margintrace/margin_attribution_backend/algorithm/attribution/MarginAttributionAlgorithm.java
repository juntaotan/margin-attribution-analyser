package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

/**
 * This class implements the margin attribution algorithm.
 */
public class MarginAttributionAlgorithm {
    /**
     * Reconstructs every complete path from an upstream source to a target position.
     *
     * @param graph dependency graph whose edges point from upstream to downstream
     * @param position target position in {@link CsrGraph#nodes()}
     * @return complete paths in source-to-target order, including the target position
     */
    public int[][] reverseTracing(CsrGraph graph, int position) {
        validateGraph(graph);

        int nodeCount = graph.nodes().length;
        if (position < 0 || position >= nodeCount) {
            throw new IndexOutOfBoundsException(
                    "position must be between 0 and " + (nodeCount - 1) + ": " + position
            );
        }

        int[] reverseOffsets = calculateReverseOffsets(nodeCount, graph.successors());
        int[] predecessors = buildPredecessors(graph, reverseOffsets);

        List<int[]> paths = new ArrayList<>();
        tracePaths(
                position,
                reverseOffsets,
                predecessors,
                new int[nodeCount],
                0,
                new boolean[nodeCount],
                paths
        );
        return paths.toArray(int[][]::new);
    }

    private void tracePaths(
            int position,
            int[] reverseOffsets,
            int[] predecessors,
            int[] reversePath,
            int pathLength,
            boolean[] positionsOnPath,
            List<int[]> paths
    ) {
        if (positionsOnPath[position]) {
            throw new IllegalArgumentException("graph contains a cycle at position: " + position);
        }

        positionsOnPath[position] = true;
        reversePath[pathLength] = position;

        int predecessorStart = reverseOffsets[position];
        int predecessorEnd = reverseOffsets[position + 1];
        if (predecessorStart == predecessorEnd) {
            int[] path = new int[pathLength + 1];
            for (int index = 0; index <= pathLength; index++) {
                path[index] = reversePath[pathLength - index];
            }
            paths.add(path);
        } else {
            for (int edgeIndex = predecessorStart; edgeIndex < predecessorEnd; edgeIndex++) {
                tracePaths(
                        predecessors[edgeIndex],
                        reverseOffsets,
                        predecessors,
                        reversePath,
                        pathLength + 1,
                        positionsOnPath,
                        paths
                );
            }
        }

        positionsOnPath[position] = false;
    }

    private int[] calculateReverseOffsets(int nodeCount, int[] successors) {
        int[] incomingEdgeCounts = new int[nodeCount];
        for (int successor : successors) {
            incomingEdgeCounts[successor]++;
        }

        int[] reverseOffsets = new int[nodeCount + 1];
        for (int nodeIndex = 0; nodeIndex < nodeCount; nodeIndex++) {
            reverseOffsets[nodeIndex + 1] = reverseOffsets[nodeIndex] + incomingEdgeCounts[nodeIndex];
        }
        return reverseOffsets;
    }

    private int[] buildPredecessors(CsrGraph graph, int[] reverseOffsets) {
        int nodeCount = graph.nodes().length;
        int[] predecessors = new int[graph.successors().length];
        int[] insertionOffsets = Arrays.copyOf(reverseOffsets, nodeCount);

        for (int predecessor = 0; predecessor < nodeCount; predecessor++) {
            for (int edgeIndex = graph.offset()[predecessor];
                 edgeIndex < graph.offset()[predecessor + 1];
                 edgeIndex++) {
                int successor = graph.successors()[edgeIndex];
                predecessors[insertionOffsets[successor]++] = predecessor;
            }
        }
        return predecessors;
    }

    private void validateGraph(CsrGraph graph) {
        Objects.requireNonNull(graph, "graph must not be null");
        Node[] nodes = Objects.requireNonNull(graph.nodes(), "graph nodes must not be null");
        int[] offsets = Objects.requireNonNull(graph.offset(), "graph offsets must not be null");
        int[] successors = Objects.requireNonNull(
                graph.successors(),
                "graph successors must not be null"
        );

        if (offsets.length != nodes.length + 1) {
            throw new IllegalArgumentException("graph offsets length must equal node count + 1");
        }
        if (offsets[0] != 0 || offsets[offsets.length - 1] != successors.length) {
            throw new IllegalArgumentException("graph offsets must span the successors array");
        }

        for (int offsetIndex = 1; offsetIndex < offsets.length; offsetIndex++) {
            if (offsets[offsetIndex] < offsets[offsetIndex - 1]) {
                throw new IllegalArgumentException("graph offsets must be non-decreasing");
            }
        }
        for (int successor : successors) {
            if (successor < 0 || successor >= nodes.length) {
                throw new IllegalArgumentException("graph contains an invalid successor position: " + successor);
            }
        }
    }
}
