package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Data passed between handlers for one trace invocation. */
final class TraceContext {
    final LocalDate startDate;
    final LocalDate endDate;
    List<String> targets = List.of();
    final LinkedHashSet<Node> nodes = new LinkedHashSet<>();
    final Map<String, Node> producedNodesById = new LinkedHashMap<>();
    Map<Node, List<Node>> materialUsage = Map.of();
    CsrGraph graph;
    CsrResult result;

    TraceContext(LocalDate startDate, LocalDate endDate) {
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
