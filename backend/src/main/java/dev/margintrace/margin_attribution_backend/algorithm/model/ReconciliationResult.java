package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.util.List;
import java.util.Map;

/** Edge scores use positions local to the corresponding graph. */
public record ReconciliationResult(
        Map<GraphEdge, Integer> actualEdgeScores,
        Map<GraphEdge, Integer> comparableEdgeScores,
        List<Integer> materialLeafPositions,
        List<PropagationPath> paths
) {
    public ReconciliationResult {
        actualEdgeScores = Map.copyOf(actualEdgeScores);
        comparableEdgeScores = Map.copyOf(comparableEdgeScores);
        materialLeafPositions = List.copyOf(materialLeafPositions);
        paths = List.copyOf(paths);
    }
}
