package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;

import java.math.BigDecimal;

/** Two fully built CSR graphs and the cost thresholds for their comparison. */
public record ReconciliationAnalysisRequest(
        CsrGraph actualGraph,
        CsrGraph comparableGraph,
        BigDecimal leafThreshold,
        BigDecimal stopThreshold
) {
}
