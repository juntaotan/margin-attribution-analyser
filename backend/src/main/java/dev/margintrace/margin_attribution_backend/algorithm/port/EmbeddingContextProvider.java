package dev.margintrace.margin_attribution_backend.algorithm.port;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.MaterialContext.Evidence;

/** Verified occurrence-scoped descriptions and financial/source context, never inferred suppliers. */
@FunctionalInterface
public interface EmbeddingContextProvider {
    Evidence evidence(ProductionTree tree, ProductionTree.Occurrence occurrence);
}
