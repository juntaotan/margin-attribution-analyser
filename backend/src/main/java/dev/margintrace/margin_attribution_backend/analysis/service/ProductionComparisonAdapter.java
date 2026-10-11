package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFgwAdapter;
import dev.margintrace.margin_attribution_backend.analysis.dto.*;
import java.util.*;

/** Compatibility translation for the existing supplied-vector HTTP contract. */
public final class ProductionComparisonAdapter {
    public ProductionComparisonResponse compare(ProductionTreeResponse trees, ProductionComparisonRequest request) {
        var vectors = new HashMap<String, ProductionTreeFgwAdapter.Embedding>();
        if (request.embeddings() != null) request.embeddings().forEach((id, v) -> {
            if (v != null) vectors.put(id, new ProductionTreeFgwAdapter.Embedding(v.version(), v.values()));
        });
        var result = new ProductionTreeFgwAdapter().compare(trees.trees(), new ProductionTreeFgwAdapter.Request(
                request.leftProductionId(), request.rightProductionId(), vectors, request.config(), request.initialMatrices()));
        return new ProductionComparisonResponse(result.statuses(), result.missingVectors(), result.embeddingVersions(),
                trees, result.result(), result.converged());
    }
}
