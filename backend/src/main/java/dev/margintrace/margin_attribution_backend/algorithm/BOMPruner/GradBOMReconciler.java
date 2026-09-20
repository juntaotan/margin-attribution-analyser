package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.algorithm.model.ReconciliationResult;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Entry point for edge scoring, material leaf selection, and cost propagation. */
public class GradBOMReconciler {
    private final PruningHandler chain = new EdgeSimilarityHandler(
            new MaterialLeafSelector(new CostPropagationHandler()));

    public ReconciliationResult reconcile(CsrGraph actualGraph, CsrGraph comparableGraph,
                                          BigDecimal leafThreshold, BigDecimal stopThreshold) {
        validateGraph(actualGraph, "actualGraph");
        validateGraph(comparableGraph, "comparableGraph");
        validateThreshold(leafThreshold, "leafThreshold");
        validateThreshold(stopThreshold, "stopThreshold");

        Map<String, Integer> comparablePositions = positionsById(comparableGraph, "comparableGraph");
        positionsById(actualGraph, "actualGraph");
        PruningContext context = new PruningContext(actualGraph, comparableGraph,
                leafThreshold, stopThreshold, comparablePositions);
        chain.handle(context);
        return context.result();
    }

    private void validateThreshold(BigDecimal threshold, String name) {
        Objects.requireNonNull(threshold, name + " must not be null");
        if (threshold.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private void validateGraph(CsrGraph graph, String name) {
        Objects.requireNonNull(graph, name + " must not be null");
        Node[] nodes = Objects.requireNonNull(graph.nodes(), name + " nodes must not be null");
        int[] offset = Objects.requireNonNull(graph.offset(), name + " offset must not be null");
        int[] successors = Objects.requireNonNull(graph.successors(), name + " successors must not be null");
        if (offset.length != nodes.length + 1 || offset[0] != 0 || offset[nodes.length] != successors.length) {
            throw new IllegalArgumentException(name + " has invalid CSR offsets");
        }
        for (int position = 0; position < nodes.length; position++) {
            Objects.requireNonNull(nodes[position], name + " contains a null node at " + position);
            if (offset[position] > offset[position + 1]) {
                throw new IllegalArgumentException(name + " offsets must be non-decreasing");
            }
        }
        for (int successor : successors) {
            if (successor < 0 || successor >= nodes.length) {
                throw new IllegalArgumentException(name + " contains invalid successor: " + successor);
            }
        }
    }

    private Map<String, Integer> positionsById(CsrGraph graph, String name) {
        Map<String, Integer> positions = new HashMap<>();
        for (int position = 0; position < graph.nodes().length; position++) {
            String id = graph.nodes()[position].inventoryId();
            if (positions.putIfAbsent(id, position) != null) {
                throw new IllegalArgumentException(name + " contains duplicate inventoryId: " + id);
            }
        }
        return positions;
    }
}
