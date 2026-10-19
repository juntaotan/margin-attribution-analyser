package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingOutcome;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionReport;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.util.*;

/** Connected discovery, source, context and comparison evidence with explicit partial outcomes. */
public record AnalysisOutcome(
        UUID analysisId,
        String status,
        PeriodGraphProvider.Snapshot actual,
        PeriodGraphProvider.Snapshot comparable,
        List<PropagationPath> branches,
        ProductionDataProvider.Selection selection,
        EmbeddingOutcome embeddings,
        List<PairResult> pairs,
        int planned,
        int executed,
        int rejected,
        int failed,
        List<ExecutionReport> execution,
        List<String> diagnostics) {
    public record PairResult(
            String namespace,
            String productId,
            String leftProductionId,
            String rightProductionId,
            String disposition,
            ProductionTreeFgwAdapter.Result comparison,
            String diagnostic) {}
}
