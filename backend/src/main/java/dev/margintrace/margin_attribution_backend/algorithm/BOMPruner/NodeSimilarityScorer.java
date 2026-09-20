package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

import java.util.Objects;
import java.util.Set;

/** Builds node and directed-edge embeddings for edge similarity scoring. */
public class NodeSimilarityScorer {

    /**
     * Features used to identify a similar node.
     * More node features can be added here when the matching strategy evolves.
     */
    public record Embedding(String inventoryId) {}

    /** A directed edge is identified by its two endpoint inventory IDs. */
    public record EdgeEmbedding(Embedding from, Embedding to) {}

    /** Builds the current ID-based embedding for a node. */
    public Embedding buildEmbedding(Node node) {
        Objects.requireNonNull(node, "node must not be null");
        return new Embedding(node.inventoryId());
    }

    public EdgeEmbedding buildEdgeEmbedding(Node from, Node to) {
        return new EdgeEmbedding(buildEmbedding(from), buildEmbedding(to));
    }

    public int scoreEdge(EdgeEmbedding edge, Set<EdgeEmbedding> candidates) {
        Objects.requireNonNull(edge, "edge embedding must not be null");
        Objects.requireNonNull(candidates, "candidate edges must not be null");
        return candidates.contains(edge) ? 1 : 0;
    }
}
