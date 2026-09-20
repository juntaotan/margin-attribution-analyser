package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** Follows successors from each material leaf and records every traversed edge. */
final class CostPropagationHandler extends PruningHandler {
    private static final Logger log = LoggerFactory.getLogger(CostPropagationHandler.class);

    CostPropagationHandler() {
        super(null);
    }

    @Override
    protected void process(PruningContext context) {
        Queue<State> queue = new ArrayDeque<>();
        for (int leaf : context.materialLeafPositions) {
            queue.add(new State(List.of(leaf), List.of()));
        }
        while (!queue.isEmpty()) {
            State state = queue.remove();
            int from = state.positions().getLast();
            int start = context.actualGraph.offset()[from];
            int end = context.actualGraph.offset()[from + 1];
            if (start == end) {
                context.paths.add(new PropagationPath(state.positions(), state.edges(),
                        PropagationPath.EndReason.GRAPH_END, context.costDifference(from)));
                continue;
            }
            for (int index = start; index < end; index++) {
                int to = context.actualGraph.successors()[index];
                if (state.positions().contains(to)) {
                    throw new IllegalArgumentException("graph contains a cycle at position: " + to);
                }
                GraphEdge edge = new GraphEdge(from, to, index);
                List<Integer> positions = new ArrayList<>(state.positions());
                positions.add(to);
                List<GraphEdge> edges = new ArrayList<>(state.edges());
                edges.add(edge);
                BigDecimal difference = context.costDifference(to);
                log.info("Propagation edge {} -> {} (positions {} -> {}, edge index {}, cost difference {})",
                        context.actualGraph.nodes()[from].inventoryId(),
                        context.actualGraph.nodes()[to].inventoryId(), from, to, index, difference);
                if (difference != null && difference.compareTo(context.stopThreshold) > 0) {
                    context.paths.add(new PropagationPath(positions, edges,
                            PropagationPath.EndReason.THRESHOLD_EXCEEDED, difference));
                } else {
                    queue.add(new State(List.copyOf(positions), List.copyOf(edges)));
                }
            }
        }
    }

    private record State(List<Integer> positions, List<GraphEdge> edges) {
    }
}
