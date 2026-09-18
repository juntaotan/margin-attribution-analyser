package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.MarginAttributionAlgorithm;
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
    private final MarginAttributionAlgorithm marginAttributionAlgorithm =
            new MarginAttributionAlgorithm();
    private final NodeSimilarityScorer nodeSimilarityScorer = new NodeSimilarityScorer();

    /**
     * Finds complete upstream paths that end at a node outside the allowed variance.
     *
     * @param graph graph whose stored edges point from upstream to downstream
     * @param comparableGraph graph containing quantities for comparable embeddings
     * @param startPosition downstream position at which propagation begins
     * @param comparablePoints comparable positions mapped to their embeddings
     * @param variance maximum allowed absolute quantity difference
     * @return edges from paths that exceed the variance, mapped from upstream
     *         positions to downstream positions
     */
    public Map<Integer, List<Integer>> adaptivePruning(
            CsrGraph graph,
            CsrGraph comparableGraph,
            int startPosition,
            Map<Integer, NodeSimilarityScorer.Embedding> comparablePoints,
            BigDecimal variance
    ) {

        // Stores only paths whose boundary node exceeds the allowed variance.
        Map<Integer, List<Integer>> graphDiff = new LinkedHashMap<>();

        // Each queue entry owns its path so that converging DAG branches remain distinct.
        Queue<PropagationState> propagatingQueue = new ArrayDeque<>();

        if (startPosition < 0 || startPosition >= graph.nodes().length) {
            throw new IndexOutOfBoundsException(
                    "startPosition must be between 0 and "
                            + (graph.nodes().length - 1) + ": " + startPosition
            );
        }

        propagatingQueue.add(new PropagationState(startPosition, List.of(startPosition)));

        while(!propagatingQueue.isEmpty()){
            PropagationState state = propagatingQueue.remove();
            int current = state.currentPosition();

            // Step 1: Find the direct upstream nodes of the current node, then
            // look in comparablePoints for a node with a matching embedding.
            List<Integer> upstreams =
                    marginAttributionAlgorithm.findDirectUpstreamPositions(graph, current);
            for (int upstreamPosition : upstreams) {
                // A valid DAG cannot revisit a node in the same path. This guard also
                // prevents an invalid cyclic graph from causing an infinite loop.
                if (state.path().contains(upstreamPosition)) {
                    continue;
                }

                List<Integer> upstreamPath = new ArrayList<>(state.path());
                upstreamPath.add(upstreamPosition);

                NodeSimilarityScorer.Embedding upstreamEmbedding =
                        nodeSimilarityScorer.buildEmbedding(graph.nodes()[upstreamPosition]);
                Integer comparablePosition = findMatchingComparablePosition(
                        upstreamEmbedding,
                        comparablePoints
                );

                if (comparablePosition == null) {
                    continue;
                }

                BigDecimal originalQuantity = graph.nodes()[upstreamPosition].quantity();
                BigDecimal comparableQuantity =
                        comparableGraph.nodes()[comparablePosition].quantity();

                // Step 2: Once the boundary node exceeds the allowed variance, add
                // its complete downstream-to-upstream path to graphDiff and stop
                // propagating only this path.
                if (exceedsVariance(
                        originalQuantity,
                        comparableQuantity,
                        variance)) {
                    addPathToGraphDiff(graphDiff, upstreamPath);
                    continue;
                }

                // Step 3: Keep the complete path while propagating farther upstream.
                propagatingQueue.add(new PropagationState(
                        upstreamPosition,
                        List.copyOf(upstreamPath)
                ));
            }
        }

        return graphDiff;
    }

    private void addPathToGraphDiff(
            Map<Integer, List<Integer>> graphDiff,
            List<Integer> downstreamToUpstreamPath
    ) {
        for (int index = 1; index < downstreamToUpstreamPath.size(); index++) {
            int downstreamPosition = downstreamToUpstreamPath.get(index - 1);
            int upstreamPosition = downstreamToUpstreamPath.get(index);
            List<Integer> downstreamPositions =
                    graphDiff.computeIfAbsent(upstreamPosition, ignored -> new ArrayList<>());

            if (!downstreamPositions.contains(downstreamPosition)) {
                downstreamPositions.add(downstreamPosition);
            }
        }
    }

    private Integer findMatchingComparablePosition(
            NodeSimilarityScorer.Embedding source,
            Map<Integer, NodeSimilarityScorer.Embedding> comparablePoints
    ) {
        for (Map.Entry<Integer, NodeSimilarityScorer.Embedding> entry
                : comparablePoints.entrySet()) {
            if (nodeSimilarityScorer.match(source, entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean exceedsVariance(
            BigDecimal original,
            BigDecimal comparable,
            BigDecimal threshold
    ) {
        return original.subtract(comparable)
                .abs()
                .compareTo(threshold) > 0;
    }

    private record PropagationState(int currentPosition, List<Integer> path) {
    }
}
