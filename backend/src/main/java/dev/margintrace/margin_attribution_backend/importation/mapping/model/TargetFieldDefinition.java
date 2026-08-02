package dev.margintrace.margin_attribution_backend.importation.mapping.model;

import dev.margintrace.margin_attribution_backend.importation.model.DataType;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Defines one canonical field that an imported source column can be mapped to.
 *
 * @param fieldKey stable business-facing identifier used by mapping APIs
 * @param columnName physical column name in the canonical database table
 * @param dataType expected logical type of the imported value
 * @param required whether a mapping must be supplied before the import can be confirmed
 * @param aliases known source-header names for this field
 */
public record TargetFieldDefinition(
        String fieldKey,
        String columnName,
        DataType dataType,
        boolean required,
        Set<String> aliases
) {
    public TargetFieldDefinition {
        requireText(fieldKey, "Field key");
        requireText(columnName, "Column name");
        if (dataType == null) {
            throw new IllegalArgumentException("Data type must not be null");
        }

        LinkedHashSet<String> allAliases = new LinkedHashSet<>();
        allAliases.add(fieldKey);
        allAliases.add(columnName);
        if (aliases != null) {
            aliases.forEach(alias -> {
                requireText(alias, "Alias");
                allAliases.add(alias.trim());
            });
        }
        aliases = Set.copyOf(allAliases);
    }

    private static void requireText(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
    }
}
