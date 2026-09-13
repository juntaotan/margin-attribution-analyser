package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Analyser {

    private final AttributionWorkflow attributionWorkflow;

    /**
     * Runs the attribution workflow and turns its source-to-target index paths into
     * a Node-to-direct-downstream-nodes adjacency map. The graph is used only to
     * resolve path indexes; nodes and edges outside the traced paths are excluded.
     * Repeated nodes and edges from converging paths or multiple targets are merged.
     *
     * @param targets target inventory IDs; empty or null requests every product in the period
     * @param startDate inclusive start of the production period
     * @param endDate inclusive end of the production period
     * @return one entry per traced Node, including terminal nodes with no downstream nodes
     */
    public AnalysisResults analyser(List<String> targets, LocalDate startDate, LocalDate endDate) {
        CsrResult csrResult = attributionWorkflow.trace(startDate, endDate, targets);
        Node[] graphNodes = csrResult.csrGraph().nodes();
        Map<Node, LinkedHashSet<Node>> adjacency = new LinkedHashMap<>();

        // Each target has its own paths, but the frontend needs one combined graph.
        // LinkedHashMap retains first-seen order; LinkedHashSet removes repeated edges.
        for (int[][] targetPaths : csrResult.pathsByTarget().values()) {
            // A path is an ordered sequence of indexes into the shared CSR graph.
            // Process every path because two paths can share upstream sections.
            for (int[] path : targetPaths) {
                // Add every visited node as a key, including a one-node path and the
                // terminal target; these nodes have an empty downstream list if needed.
                for (int position = 0; position < path.length; position++) {
                    Node upstream = graphNodes[path[position]];
                    LinkedHashSet<Node> downstream = adjacency.computeIfAbsent(
                            upstream, ignored -> new LinkedHashSet<>());

                    // Consecutive positions form one direct upstream-to-downstream
                    // edge. A terminal node has no next position and keeps its key.
                    if (position + 1 < path.length) {
                        downstream.add(graphNodes[path[position + 1]]);
                    }
                }
            }
        }

        List<AnalysisAdjacencyEntry> entries = new ArrayList<>(adjacency.size());
        // JSON object keys cannot contain Node objects, so expose each map entry as
        // a pair while preserving the exact Node values and direct relationships.
        for (Map.Entry<Node, LinkedHashSet<Node>> entry : adjacency.entrySet()) {
            entries.add(new AnalysisAdjacencyEntry(
                    entry.getKey(), List.copyOf(entry.getValue())));
        }

        return AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.copyOf(entries))
                .build();
    }
}
