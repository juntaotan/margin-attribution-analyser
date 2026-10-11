package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Independent workflow input. Monetary thresholds retain exact decimal values. */
public record AnalysisCommand(LocalDate actualStartDate, LocalDate actualEndDate,
                              LocalDate comparableStartDate, LocalDate comparableEndDate,
                              BigDecimal leafThreshold, BigDecimal stopThreshold, ExecutionPolicy execution,
                              EmbeddingSettings embedding, Comparison comparison) {
    public record Comparison(ProductionTree.Evidence evidence, boolean completeGraphCoverage, Map<String, MaterialContext.Evidence> contextEvidence,
                             Map<String, EmbeddingOutcome.Supplied> embeddings, FgwEngine.Config config,
                             WarehouseOptions warehouse) {
        public Comparison(ProductionTree.Evidence evidence, boolean completeGraphCoverage,
                          Map<String, MaterialContext.Evidence> contextEvidence,
                          Map<String, EmbeddingOutcome.Supplied> embeddings, FgwEngine.Config config) {
            this(evidence, completeGraphCoverage, contextEvidence, embeddings, config, null);
        }
    }

    /** Explicit caller declarations for adapting the existing warehouse to direct-consumption analysis. */
    public record WarehouseOptions(String namespace, String currency, String costScope,
                                   ProductionTree.OutputInterpretation quantityInterpretation,
                                   ProductionTree.OutputInterpretation costInterpretation) {}
}
