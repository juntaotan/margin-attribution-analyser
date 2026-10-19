package dev.margintrace.margin_attribution_backend.report.ai;

/**
 * AI answer for period detection: whether a period is stated and, if so, the period as written.
 */
public record PeriodExtraction(boolean found, String duration) {}
