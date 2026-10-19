package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import dev.margintrace.margin_attribution_backend.report.ai.BlueprintInputField;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprintAssistant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Turns a natural-language placeholder prompt into a validated query blueprint using the AI blueprint assistant.
 *
 * <p>The AI output is not trusted: the source table must be one of the warehouse tables, the format one of
 * {@code percentage}, {@code currency}, {@code number} or {@code text}, and every required field present. SQL fragments
 * are checked separately by {@link ReportQueryService} before execution.
 */
@Service
@RequiredArgsConstructor
public class ReportBlueprintService {
    private static final Set<String> ALLOWED_TABLES =
            Set.of("material_master", "inventory_usage", "bill_of_material", "sales_details", "purchases");

    private static final Set<String> FORMATS = Set.of("percentage", "currency", "number", "text");

    private final ReportBlueprintAssistant blueprintAssistant;
    private final AiErrorMapper aiErrorMapper;

    /**
     * @param prompt the placeholder prompt, at most 10,000 characters
     * @return the blueprint with trimmed fields and blank filters removed
     * @throws IllegalArgumentException if the prompt is blank or too long
     * @throws AiUnavailableException if the AI service fails or returns an invalid blueprint
     */
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
            throw new AiUnavailableException("llama.cpp selected an unknown source table: " + sourceTable);
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
        List<BlueprintInputField> inputFields =
                blueprint.inputFields().stream().map(this::validateField).toList();

        String format = required(blueprint.format(), "format").toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(format)) {
            throw new AiUnavailableException("llama.cpp returned an invalid output format");
        }

        String label = blueprint.sourceTableLabel() == null
                        || blueprint.sourceTableLabel().isBlank()
                ? sourceTable
                : blueprint.sourceTableLabel().trim();

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
