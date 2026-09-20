package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Packages the graph and the sold targets' upstream paths for the current API. */
@Component
public class GraphResultHandler extends AbstractTraceHandler {
    public GraphResultHandler() {
        super(null);
    }

    @Override
    protected void execute(TraceContext context) {
        CsrGraph graph = context.graph;
        Node[] nodes = graph.nodes();
        Map<Node, Integer> positions = new HashMap<>();
        for (int index = 0; index < nodes.length; index++) {
            positions.put(nodes[index], index);
        }

        int[] reverseOffset = new int[nodes.length + 1];
        for (int successor : graph.successors()) {
            reverseOffset[successor + 1]++;
        }
        for (int index = 0; index < nodes.length; index++) {
            reverseOffset[index + 1] += reverseOffset[index];
        }
        int[] predecessors = new int[graph.successors().length];
        int[] nextInsertion = Arrays.copyOf(reverseOffset, nodes.length);
        for (int upstream = 0; upstream < nodes.length; upstream++) {
            for (int edge = graph.offset()[upstream]; edge < graph.offset()[upstream + 1]; edge++) {
                int downstream = graph.successors()[edge];
                predecessors[nextInsertion[downstream]++] = upstream;
            }
        }

        Map<String, int[][]> paths = new LinkedHashMap<>();
        for (String target : context.targets) {
            List<int[]> targetPaths = new ArrayList<>();
            tracePaths(positions.get(context.producedNodesById.get(target)), reverseOffset,
                    predecessors, new int[nodes.length], 0, new boolean[nodes.length], targetPaths);
            paths.put(target, targetPaths.toArray(int[][]::new));
        }
        context.result = new CsrResult(graph, Collections.unmodifiableMap(paths));
    }

    private void tracePaths(
            int position,
            int[] reverseOffset,
            int[] predecessors,
            int[] reversePath,
            int depth,
            boolean[] onPath,
            List<int[]> paths
    ) {
        if (onPath[position]) {
            throw new IllegalArgumentException("graph contains a cycle at position: " + position);
        }
        onPath[position] = true;
        reversePath[depth] = position;

        if (reverseOffset[position] == reverseOffset[position + 1]) {
            int[] path = new int[depth + 1];
            for (int index = 0; index <= depth; index++) {
                path[index] = reversePath[depth - index];
            }
            paths.add(path);
        } else {
            for (int edge = reverseOffset[position]; edge < reverseOffset[position + 1]; edge++) {
                tracePaths(predecessors[edge], reverseOffset, predecessors,
                        reversePath, depth + 1, onPath, paths);
            }
        }
        onPath[position] = false;
    }
}
