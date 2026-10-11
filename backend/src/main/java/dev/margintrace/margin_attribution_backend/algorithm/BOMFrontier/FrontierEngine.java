package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

/** Exact micro-unit frontier boundary; implementations retain the same selected-node contract. */
@FunctionalInterface
public interface FrontierEngine {
    int[] run(BomUpwardGraph graph, long threshold);
}
