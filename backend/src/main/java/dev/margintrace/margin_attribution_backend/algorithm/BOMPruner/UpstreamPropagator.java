package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;

import java.math.BigDecimal;
import java.util.*;

/**
 * Traverses a CSR graph from a downstream node toward all reachable upstream nodes.
 *
 * <p>This initial framework propagates node positions only. Future versions can attach
 * a variance or embedding payload to each queued position and apply an allocation rule
 * when one node has multiple upstream predecessors.</p>
 */
public class UpstreamPropagator {

    /**
     * Visits the starting position and every node reachable through incoming edges.
     * Each position is returned once, in breadth-first downstream-to-upstream order.
     *
     * @param graph graph whose stored edges point from upstream to downstream
     * @param startPosition downstream position at which propagation begins
     * @return visited node positions, including {@code startPosition}
     */
    public List<Integer> adaptivePruning(
            CsrGraph graph,
            int startPosition,
            Map<Integer, BigDecimal> comparablePoints,
            BigDecimal variance
    ) {

        Queue<Integer> propagatingQueue = new ArrayDeque<>();
        Map<Integer, Integer> graphDiff = new LinkedHashMap<>();
        List<Integer> result = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();

        if (startPosition < 0 || startPosition >= graph.nodes().length) {
            throw new IndexOutOfBoundsException(
                    "startPosition must be between 0 and "
                            + (graph.nodes().length - 1) + ": " + startPosition
            );
        }

        propagatingQueue.add(startPosition);
        visited.add(startPosition);

        while(!propagatingQueue.isEmpty()){
            int current = propagatingQueue.remove();
            result.add(current);

            BigDecimal comparableQuantity = comparablePoints.get(current);
            if (comparableQuantity == null
                    || isContinue(
                            graph.nodes()[current].quantity(),
                            comparableQuantity,
                            variance)) {
                continue;
            }

            List<Integer> upstreams = findDirectUpstreamPositions(graph, current);
            for (int upstreamPosition : upstreams) {
                if (visited.add(upstreamPosition)) {
                    propagatingQueue.add(upstreamPosition);
                }
            }
        }

        return result;
    }

    /**
     * Returns the positions of all nodes that point directly to the supplied node.
     *
     * @param graph graph whose CSR edges are stored from upstream to downstream
     * @param currentPosition position of the material whose direct upstream is required
     * @return direct upstream node positions
     */
    public List<Integer> findDirectUpstreamPositions(CsrGraph graph, int currentPosition) {
        if (currentPosition < 0 || currentPosition >= graph.nodes().length) {
            throw new IndexOutOfBoundsException(
                    "currentPosition must be between 0 and "
                            + (graph.nodes().length - 1) + ": " + currentPosition
            );
        }

        List<Integer> upstreamPositions = new ArrayList<>();
        for (int candidatePosition = 0;
             candidatePosition < graph.nodes().length;
             candidatePosition++) {
            int start = graph.offset()[candidatePosition];
            int end = graph.offset()[candidatePosition + 1];

            for (int edgeIndex = start; edgeIndex < end; edgeIndex++) {
                if (graph.successors()[edgeIndex] == currentPosition) {
                    upstreamPositions.add(candidatePosition);
                    break;
                }
            }
        }
        return upstreamPositions;
    }

    private boolean isContinue(BigDecimal original, BigDecimal comparable, BigDecimal threshold){
        return original.subtract(comparable)
                .abs()
                .compareTo(threshold) > 0;
    }
}
