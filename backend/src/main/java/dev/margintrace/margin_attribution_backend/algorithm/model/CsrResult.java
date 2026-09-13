package dev.margintrace.margin_attribution_backend.algorithm.model;

public record CsrResult(
        CsrGraph csrGraph,
        int[][] tracingList
) {
}
