package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A threshold path whose IDs point into the streamed actual CSR snapshot. */
public record ReconciliationStreamPath(
        List<Integer> positions,
        List<Integer> edgeIndexes,
        List<UUID> nodeIds,
        List<UUID> edgeIds,
        PropagationPath.EndReason endReason,
        BigDecimal endingCostDifference) {
    /**
     * @param path a path in the actual-period graph
     * @param analysisId the analysis whose stable node and edge IDs to use
     * @return the path with positions, edge indexes and the matching stable IDs
     */
    public static ReconciliationStreamPath from(PropagationPath path, UUID analysisId) {
        return new ReconciliationStreamPath(
                path.positions(),
                path.edges().stream().map(GraphEdge::edgeIndex).toList(),
                path.positions().stream()
                        .map(p -> StreamCsrGraph.nodeId(analysisId, "actual", p))
                        .toList(),
                path.edges().stream()
                        .map(e -> StreamCsrGraph.edgeId(analysisId, "actual", e.edgeIndex()))
                        .toList(),
                path.endReason(),
                path.endingCostDifference());
    }
}
