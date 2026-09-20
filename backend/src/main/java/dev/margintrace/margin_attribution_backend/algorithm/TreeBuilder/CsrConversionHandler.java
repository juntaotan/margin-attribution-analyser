package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Connects production stages and converts their dependencies into CSR arrays. */
@Component
public class CsrConversionHandler extends AbstractTraceHandler {
    private static final Comparator<Node> NODE_ORDER = Comparator
            .comparing(Node::inventoryId)
            .thenComparing(Node::quantity)
            .thenComparing(Node::cost, Comparator.nullsFirst(BigDecimal::compareTo));

    public CsrConversionHandler(GraphResultHandler next) {
        super(next);
    }

    @Override
    protected void execute(TraceContext context) {
        Map<Node, LinkedHashSet<Node>> edges = new LinkedHashMap<>();
        for (Node node : context.nodes) {
            edges.put(node, new LinkedHashSet<>());
        }
        context.materialUsage.forEach((material, products) ->
                edges.computeIfAbsent(material, ignored -> new LinkedHashSet<>()).addAll(products));

        // A produced component and its later consumption are separate recorded nodes.
        for (Node material : context.materialUsage.keySet()) {
            Node produced = context.producedNodesById.get(material.inventoryId());
            if (produced != null && !produced.equals(material)) {
                edges.get(produced).add(material);
            }
        }

        Node[] nodes = context.nodes.toArray(Node[]::new);
        Arrays.sort(nodes, NODE_ORDER);
        Map<Node, Integer> positions = new HashMap<>();
        for (int index = 0; index < nodes.length; index++) {
            positions.put(nodes[index], index);
        }

        int[] offset = new int[nodes.length + 1];
        for (int index = 0; index < nodes.length; index++) {
            offset[index + 1] = offset[index] + edges.get(nodes[index]).size();
        }

        int[] successors = new int[offset[nodes.length]];
        for (int index = 0; index < nodes.length; index++) {
            int edgeIndex = offset[index];
            for (Node downstream : edges.get(nodes[index])) {
                successors[edgeIndex++] = positions.get(downstream);
            }
        }
        context.graph = new CsrGraph(nodes, offset, successors);
    }
}
