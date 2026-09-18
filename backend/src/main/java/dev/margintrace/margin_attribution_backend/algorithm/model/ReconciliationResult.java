package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.util.Map;

public record ReconciliationResult(
        Map<Integer, Integer> actualGraphDiff,
        Map<Integer, Integer> comparableGraphDiff
) {
    public ReconciliationResult {
        actualGraphDiff = Map.copyOf(actualGraphDiff);
        comparableGraphDiff = Map.copyOf(comparableGraphDiff);
    }
}
