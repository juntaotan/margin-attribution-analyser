package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.util.Objects;

/** Builds and compares node embeddings. */
public class NodeSimilarityScorer {

    /**
     * Features used to identify a similar node.
     * More node features can be added here when the matching strategy evolves.
     */
    public record Embedding(String inventoryId) {}

    /** Builds the current ID-based embedding for a node. */
    public Embedding buildEmbedding(Node node) {
        Objects.requireNonNull(node, "node must not be null");
        return new Embedding(node.inventoryId());
    }

    /** Matches two embeddings according to the current similarity strategy. */
    public boolean match(Embedding source, Embedding candidate) {
        Objects.requireNonNull(source, "source embedding must not be null");
        Objects.requireNonNull(candidate, "candidate embedding must not be null");
        return source.inventoryId().equals(candidate.inventoryId());
    }

    /**
     * Scores one node against all candidate nodes using their embeddings.
     *
     * @param node node for which the embedding is calculated
     * @param candidates nodes from the other graph
     * @return {@code 1} when any candidate has the same inventory ID; otherwise {@code 0}
     */
    public int score(Node node, Node[] candidates) {
        Objects.requireNonNull(candidates, "candidate nodes must not be null");
        Embedding sourceEmbedding = buildEmbedding(node);

        for (Node candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate node must not be null");
            Embedding candidateEmbedding = buildEmbedding(candidate);
            if (match(sourceEmbedding, candidateEmbedding)) {
                return 1;
            }
        }
        return 0;
    }
}
