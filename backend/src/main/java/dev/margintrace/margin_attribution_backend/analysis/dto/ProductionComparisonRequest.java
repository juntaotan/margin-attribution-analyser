package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import java.util.List;
import java.util.Map;

/**
 * Build current warehouse trees, then compare one explicit pair synchronously.
 * Embeddings are keyed by the stable occurrence IDs returned by inspection.
 * Every vector, including each root, has its own version and 384 finite values.
 */
public record ProductionComparisonRequest(
        ProductionTreeRequest trees,
        String leftProductionId,
        String rightProductionId,
        Map<String, Embedding> embeddings,
        FgwEngine.Config config,
        List<double[]> initialMatrices) {
    public record Embedding(String version, double[] values) {}
}
