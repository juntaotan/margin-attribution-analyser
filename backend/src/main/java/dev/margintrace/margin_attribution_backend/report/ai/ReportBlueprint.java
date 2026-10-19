package dev.margintrace.margin_attribution_backend.report.ai;

import java.util.List;

/**
 * Query plan for a report placeholder, produced by the AI blueprint assistant: one source table, filter predicates,
 * the fields read, a SQL formula with its business description, the output format and an explanation for auditors.
 */
public record ReportBlueprint(
        String sourceTable,
        String sourceTableLabel,
        List<String> filterConditions,
        List<BlueprintInputField> inputFields,
        String formula,
        String formulaDescription,
        String format,
        String explanation) {}
