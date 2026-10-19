package dev.margintrace.margin_attribution_backend.report.ai;

/**
 * A table field a blueprint reads, with its business label and description.
 */
public record BlueprintInputField(String field, String label, String description) {}
