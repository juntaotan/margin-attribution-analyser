package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Converts the collected nodes and edges into a CSR graph. */
@Component
public class CsrGraphHandler extends AbstractTraceHandler {
    private static final Comparator<Node> NODE_ORDER = Comparator.comparing(Node::inventoryId);

    public CsrGraphHandler() {
        super(null);
    }

    @Override
    protected void execute(TraceContext context) {
        Node[] nodes = context.nodes.values().toArray(Node[]::new);
        Arrays.sort(nodes, NODE_ORDER);
        Map<String, Integer> positions = new HashMap<>();
        for (int index = 0; index < nodes.length; index++) {
            positions.put(nodes[index].inventoryId(), index);
        }

        int[] outdegree = new int[nodes.length];
        for (int index = 0; index < nodes.length; index++) {
            outdegree[index] = context.edges.get(nodes[index].inventoryId()).size();
        }

        int[] offset = new int[nodes.length + 1];
        for (int index = 0; index < nodes.length; index++) {
            offset[index + 1] = offset[index] + outdegree[index];
        }

        int[] successors = new int[offset[nodes.length]];
        for (int index = 0; index < nodes.length; index++) {
            int edgeIndex = offset[index];
            for (String downstream : context.edges.get(nodes[index].inventoryId())) {
                successors[edgeIndex++] = positions.get(downstream);
            }
        }
        context.graph = new CsrGraph(nodes, offset, successors);
    }
}
