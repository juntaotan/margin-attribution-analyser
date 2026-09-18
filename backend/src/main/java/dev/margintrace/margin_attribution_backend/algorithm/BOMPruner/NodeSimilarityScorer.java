package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.util.Objects;

/**
 * Produces a node's similarity embedding relative to another graph.
 *
 * <p>The initial embedding is a scalar: it is {@code 1} when the other graph
 * contains a node with the same inventory ID, and {@code 0} otherwise. A future
 * implementation should replace this scalar with a feature vector and calculate
 * embeddings for many nodes at once using vectorized operations.</p>
 */
public class NodeSimilarityScorer {

    /**
     * Scores one node against all candidate nodes using inventory-ID equality.
     *
     * @param node node for which the embedding is calculated
     * @param candidates nodes from the other graph
     * @return {@code 1} when any candidate has the same inventory ID; otherwise {@code 0}
     */
    public int score(Node node, Node[] candidates) {
        Objects.requireNonNull(node, "node must not be null");
        Objects.requireNonNull(candidates, "candidate nodes must not be null");

        for (Node candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate node must not be null");
            if (node.inventoryId().equals(candidate.inventoryId())) {
                return 1;
            }
        }
        return 0;
    }
}
