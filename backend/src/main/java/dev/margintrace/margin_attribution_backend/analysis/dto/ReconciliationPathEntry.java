package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;

import java.math.BigDecimal;
import java.util.List;

/** A complete material-to-boundary path with JSON-safe node values. */
public record ReconciliationPathEntry(
        List<Node> nodes,
        List<GraphEdge> edges,
        PropagationPath.EndReason endReason,
        BigDecimal endingCostDifference
) {
    public ReconciliationPathEntry {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
