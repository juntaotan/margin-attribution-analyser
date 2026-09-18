package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.ReconciliationResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reconciles an actual production graph with a comparable graph. */
public class GradBOMReconciler {
    private final NodeSimilarityScorer nodeSimilarityScorer = new NodeSimilarityScorer();

    /**
     * Maps every node position to its similarity embedding relative to the other graph.
     *
     * @param actualGraph graph built from actual production and consumption data
     * @param comparableGraph graph against which the actual graph will be compared
     * @param variance variance amount reserved for the reconciliation calculation
     * @return the position-to-embedding maps for both graphs
     */
    public ReconciliationResult gradBOMReconciler(CsrGraph actualGraph, CsrGraph comparableGraph, BigDecimal variance) {
        // Create two collections to contain analysis result
        Map<Integer, Integer> actualGraphDiff = new LinkedHashMap<>();
        Map<Integer, Integer> comparableGraphDiff = new LinkedHashMap<>();

        // Get all nodes and its embedding value
        Map<Integer, Integer> comparablePoints = new HashMap<>();
        for (int position = 0; position < comparableGraph.nodes().length; position++) {
            Integer embedding = nodeSimilarityScorer.score(comparableGraph.nodes()[position], actualGraph.nodes());
            comparablePoints.put(position, embedding);
        }

        return new ReconciliationResult(actualGraphDiff, comparableGraphDiff);
    }

    private void findDiffRange(CsrGraph graph, Map<Integer, Integer> comparablePoints){

    }
}
