package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

/**
 * Raw arrays for upward traversal of an internal DAG with unique terminal nodes.
 * {@code offsets} gives each product's material count by consecutive differences;
 * {@code reverseOffsets} and {@code reverseSuccessors} map each material to its products.
 * Node values are supplied values and are not accumulated during traversal.
 */
public record BomUpwardGraph(
        int[] offsets,
        int[] reverseOffsets,
        int[] reverseSuccessors,
        int[] terminalNodes,
        long[] nodeValues,
        byte[] nodeComparable) {
}
