package dev.margintrace.margin_attribution_backend.algorithm.model;

public record CsrGraph(
        Node[] nodes,
        int[] offset,
        int[] successors
) {
}
