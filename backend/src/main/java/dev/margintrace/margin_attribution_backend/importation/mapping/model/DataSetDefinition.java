package dev.margintrace.margin_attribution_backend.importation.mapping.model;

import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Defines a canonical business data set and all target fields available for schema mapping.
 */
public record DataSetDefinition(
        DataSetType type,
        String tableName,
        Set<String> aliases,
        List<TargetFieldDefinition> fields
) {
    public DataSetDefinition {
        if (type == null) {
            throw new IllegalArgumentException("Data set type must not be null");
        }
        requireText(tableName, "Table name");

        LinkedHashSet<String> allAliases = new LinkedHashSet<>();
        allAliases.add(type.name());
        allAliases.add(tableName);
        if (aliases != null) {
            aliases.forEach(alias -> {
                requireText(alias, "Data set alias");
                allAliases.add(alias.trim());
            });
        }
        aliases = Set.copyOf(allAliases);
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("A data set must define at least one target field");
        }

        Set<String> fieldKeys = new HashSet<>();
        Set<String> columnNames = new HashSet<>();
        for (TargetFieldDefinition field : fields) {
            if (!fieldKeys.add(field.fieldKey())) {
                throw new IllegalArgumentException("Duplicate field key: " + field.fieldKey());
            }
            if (!columnNames.add(field.columnName())) {
                throw new IllegalArgumentException("Duplicate target column: " + field.columnName());
            }
        }
    }

    public TargetFieldDefinition requireField(String fieldKey) {
        return fields.stream()
                .filter(field -> field.fieldKey().equals(fieldKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown field '%s' for data set %s".formatted(fieldKey, type)
                ));
    }

    private static void requireText(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
    }
}
