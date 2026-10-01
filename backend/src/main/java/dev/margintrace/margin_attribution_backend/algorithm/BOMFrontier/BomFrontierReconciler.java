package dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.GraphEdge;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/** Connects graph preparation and frontier pruning to the path-based analysis API. */
public final class BomFrontierReconciler {
    private final BomGraphPreparer preparer = new BomGraphPreparer();
    private final BomUpwardEngine engine = new BomUpwardEngine();

    /**
     * Finds threshold boundary nodes and reconstructs material-to-boundary paths for display.
     * Path reconstruction stays outside the frontier engine so the serial and OpenCL cores
     * can share the same compact selected-node result.
     */
    public List<PropagationPath> reconcile(CsrGraph actual, CsrGraph comparable,
            BigDecimal leafThreshold, BigDecimal stopThreshold) {
        BomGraphPreparer.PreparedPruning prepared = preparer.prepare(
                actual, comparable, leafThreshold, stopThreshold);
        int[] selectedNodes = engine.run(prepared.graph(), prepared.threshold());
        boolean[] selected = new boolean[actual.nodes().length];
        for (int node : selectedNodes) {
            selected[node] = true;
        }

        List<PropagationPath> paths = new ArrayList<>();
        Queue<PathState> pending = new ArrayDeque<>();
        for (int terminal : prepared.graph().terminalNodes()) {
            pending.add(new PathState(List.of(terminal), List.of()));
        }

        while (!pending.isEmpty()) {
            PathState state = pending.remove();
            int from = state.positions().getLast();
            for (int edgeIndex = actual.offset()[from];
                    edgeIndex < actual.offset()[from + 1]; edgeIndex++) {
                int to = actual.successors()[edgeIndex];
                List<Integer> positions = append(state.positions(), to);
                List<GraphEdge> edges = append(state.edges(), new GraphEdge(from, to, edgeIndex));
                if (selected[to]) {
                    BigDecimal difference = BigDecimal.valueOf(
                            prepared.graph().nodeValues()[to], BomGraphPreparer.VALUE_SCALE).abs();
                    paths.add(new PropagationPath(positions, edges,
                            PropagationPath.EndReason.THRESHOLD_EXCEEDED, difference));
                } else {
                    pending.add(new PathState(positions, edges));
                }
            }
        }
        return List.copyOf(paths);
    }

    /** Returns an immutable copy with one appended path element. */
    private static <T> List<T> append(List<T> values, T value) {
        List<T> result = new ArrayList<>(values.size() + 1);
        result.addAll(values);
        result.add(value);
        return List.copyOf(result);
    }

    /** One material-to-product path being reconstructed. */
    private record PathState(List<Integer> positions, List<GraphEdge> edges) {
    }
}
