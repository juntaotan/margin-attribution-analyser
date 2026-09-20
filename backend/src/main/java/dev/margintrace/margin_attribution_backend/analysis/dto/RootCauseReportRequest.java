package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.time.LocalDate;

public record RootCauseReportRequest(
        LocalDate actualStartDate, LocalDate actualEndDate,
        LocalDate comparableStartDate, LocalDate comparableEndDate,
        String inventoryId) { }
