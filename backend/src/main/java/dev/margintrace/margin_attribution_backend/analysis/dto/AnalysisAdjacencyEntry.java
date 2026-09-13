package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.util.List;

/** JSON-safe form of one entry in the Node-to-direct-downstream-nodes map. */
public record AnalysisAdjacencyEntry(Node upstream, List<Node> downstream) {
}
