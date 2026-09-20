package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** One material-to-product path; each branch gets its own path. */
public record PropagationPath(
        List<Integer> positions,
        List<GraphEdge> edges,
        EndReason endReason,
        BigDecimal endingCostDifference
) {
    public enum EndReason { THRESHOLD_EXCEEDED, GRAPH_END }

    public PropagationPath {
        positions = List.copyOf(positions);
        edges = List.copyOf(edges);
        Objects.requireNonNull(endReason, "endReason must not be null");
        if (positions.size() != edges.size() + 1) {
            throw new IllegalArgumentException("path must contain one more position than edges");
        }
    }
}
