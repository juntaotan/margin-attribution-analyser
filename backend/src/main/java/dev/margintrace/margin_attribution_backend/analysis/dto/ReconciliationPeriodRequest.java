package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Two periods from which the analyser builds the graphs to compare. */
public record ReconciliationPeriodRequest(
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        LocalDate comparableStartDate,
        LocalDate comparableEndDate,
        BigDecimal leafThreshold,
        BigDecimal stopThreshold
) {
}
