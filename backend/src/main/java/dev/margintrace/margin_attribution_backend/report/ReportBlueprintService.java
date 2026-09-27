package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import dev.margintrace.margin_attribution_backend.report.ai.BlueprintInputField;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ReportBlueprintService {
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "production_order", "inventory_usage", "bill_of_material", "sales_order",
            "cost_details", "order_margin_summary", "account_receivables", "purchases", "account_payables");

    private static final Set<String> FORMATS =
            Set.of("percentage", "currency", "number", "text");

    private final ReportBlueprintAssistant blueprintAssistant;
    private final AiErrorMapper aiErrorMapper;

    public ReportBlueprint generate(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 10_000) {
            throw new IllegalArgumentException("Enter a valid prompt before generating a Blueprint");
        }

        try {
            return validate(blueprintAssistant.generate(prompt));
        } catch (AiUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiUnavailableException(aiErrorMapper.message(exception), exception);
        }
    }

    private ReportBlueprint validate(ReportBlueprint blueprint) {
        if (blueprint == null) {
            throw new AiUnavailableException("llama.cpp returned an invalid Blueprint");
        }

        String sourceTable = required(blueprint.sourceTable(), "sourceTable");
        if (!ALLOWED_TABLES.contains(sourceTable)) {
            throw new AiUnavailableException(
                    "llama.cpp selected an unknown source table: " + sourceTable);
        }

        List<String> filters = blueprint.filterConditions() == null
                ? List.of()
                : blueprint.filterConditions().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();

        if (blueprint.inputFields() == null) {
            throw new AiUnavailableException("llama.cpp returned invalid Blueprint input fields");
        }
        List<BlueprintInputField> inputFields = blueprint.inputFields().stream()
                .map(this::validateField)
                .toList();

        String format = required(blueprint.format(), "format").toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(format)) {
            throw new AiUnavailableException("llama.cpp returned an invalid output format");
        }

        String label = blueprint.sourceTableLabel() == null
                || blueprint.sourceTableLabel().isBlank()
                ? sourceTable : blueprint.sourceTableLabel().trim();

        return new ReportBlueprint(
                sourceTable,
                label,
                filters,
                inputFields,
                required(blueprint.formula(), "formula"),
                required(blueprint.formulaDescription(), "formulaDescription"),
                format,
                required(blueprint.explanation(), "explanation"));
    }

    private BlueprintInputField validateField(BlueprintInputField field) {
        if (field == null) {
            throw new AiUnavailableException("llama.cpp returned an invalid Blueprint input field");
        }
        String name = required(field.field(), "inputFields.field");
        return new BlueprintInputField(
                name,
                required(field.label(), "inputFields.label"),
                required(field.description(), "inputFields.description"));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AiUnavailableException("llama.cpp returned an invalid Blueprint field: " + field);
        }
        return value.trim();
    }
}
