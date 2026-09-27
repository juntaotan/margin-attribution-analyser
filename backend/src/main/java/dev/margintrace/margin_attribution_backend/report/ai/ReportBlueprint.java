package dev.margintrace.margin_attribution_backend.report.ai;

import java.util.List;

public record ReportBlueprint(
        String sourceTable,
        String sourceTableLabel,
        List<String> filterConditions,
        List<BlueprintInputField> inputFields,
        String formula,
        String formulaDescription,
        String format,
        String explanation) {
}
