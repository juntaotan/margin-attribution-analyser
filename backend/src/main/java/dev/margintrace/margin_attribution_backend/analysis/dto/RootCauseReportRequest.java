package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.time.LocalDate;

/**
 * Inventory item to explain, with the inclusive current (actual) and comparison periods.
 */
public record RootCauseReportRequest(
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        LocalDate comparableStartDate,
        LocalDate comparableEndDate,
        String inventoryId) {}
