package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;

public record RootCauseReportResponse(
        String inventoryId, String category, String categoryLabel,
        String certainty, List<String> evidence,
        String summary, boolean aiGenerated, String aiMessage) { }
