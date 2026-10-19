package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;

/**
 * Calculated root-cause classification of an inventory item, with its certainty and evidence. {@code summary} is
 * the AI-written commentary when {@code aiGenerated} is true; otherwise {@code aiMessage} explains why it is
 * missing.
 */
public record RootCauseReportResponse(
        String inventoryId,
        String category,
        String categoryLabel,
        String certainty,
        List<String> evidence,
        String summary,
        boolean aiGenerated,
        String aiMessage) {}
