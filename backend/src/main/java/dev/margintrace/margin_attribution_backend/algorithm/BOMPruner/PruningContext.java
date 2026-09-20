package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import dev.margintrace.margin_attribution_backend.algorithm.model.ReconciliationResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable state scoped to one reconciliation call. */
final class PruningContext {
    final CsrGraph actualGraph;
    final CsrGraph comparableGraph;
    final BigDecimal leafThreshold;
    final BigDecimal stopThreshold;
    final Map<String, Integer> comparablePositions;
    final Map<GraphEdge, Integer> actualEdgeScores = new LinkedHashMap<>();
    final Map<GraphEdge, Integer> comparableEdgeScores = new LinkedHashMap<>();
    final List<Integer> materialLeafPositions = new ArrayList<>();
    final List<PropagationPath> paths = new ArrayList<>();

    PruningContext(CsrGraph actualGraph, CsrGraph comparableGraph,
                   BigDecimal leafThreshold, BigDecimal stopThreshold,
                   Map<String, Integer> comparablePositions) {
        this.actualGraph = actualGraph;
        this.comparableGraph = comparableGraph;
        this.leafThreshold = leafThreshold;
        this.stopThreshold = stopThreshold;
        this.comparablePositions = comparablePositions;
    }

    /** Missing IDs have no comparable cost and cannot trigger a threshold. */
    BigDecimal costDifference(int actualPosition) {
        String id = actualGraph.nodes()[actualPosition].inventoryId();
        Integer comparablePosition = comparablePositions.get(id);
        if (comparablePosition == null) {
            return null;
        }
        BigDecimal actualCost = actualGraph.nodes()[actualPosition].cost();
        BigDecimal comparableCost = comparableGraph.nodes()[comparablePosition].cost();
        if (actualCost == null || comparableCost == null) {
            throw new IllegalArgumentException("cost is required in both graphs for inventoryId: " + id);
        }
        BigDecimal difference = actualCost.subtract(comparableCost).abs();
        return difference.setScale(Math.max(0, Math.max(actualCost.scale(), comparableCost.scale())));
    }

    ReconciliationResult result() {
        return new ReconciliationResult(actualEdgeScores, comparableEdgeScores,
                materialLeafPositions, paths);
    }
}
