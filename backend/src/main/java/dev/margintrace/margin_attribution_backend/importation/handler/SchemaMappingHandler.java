package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SchemaMappingHandler extends AbstractImportHandler {
    private final SchemaMappingPresetCatalog presetCatalog;

    @Override
    public void doImport(ImportContext context) {
        if (context == null || context.getTableStructure() == null) {
            throw new IllegalArgumentException("Detected table structure is required for schema mapping");
        }
        if (context.getColumnTypes() == null || context.getColumnTypes().isEmpty()) {
            throw new IllegalArgumentException("Detected source columns are required for schema mapping");
        }

        DataSetDefinition dataSet = presetCatalog.getRequiredByTableName(
                context.getTableStructure().sheetName()
        );
        LinkedHashMap<String, TargetFieldDefinition> mappings = new LinkedHashMap<>();
        Set<String> mappedTargetColumns = new LinkedHashSet<>();

        for (String sourceColumn : context.getColumnTypes().keySet()) {
            TargetFieldDefinition targetField = findTargetField(dataSet, sourceColumn);
            if (targetField == null) {
                continue;
            }
            if (!mappedTargetColumns.add(targetField.columnName())) {
                throw new IllegalArgumentException(
                        "Multiple source columns map to target column '" + targetField.columnName() + "'"
                );
            }
            mappings.put(sourceColumn, targetField);
        }

        Set<String> missingRequiredFields = new LinkedHashSet<>();
        for (TargetFieldDefinition field : dataSet.fields()) {
            if (field.required() && !mappedTargetColumns.contains(field.columnName())) {
                missingRequiredFields.add(field.fieldKey());
            }
        }
        if (!missingRequiredFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "Missing required mappings for table '" + dataSet.tableName() + "': "
                            + String.join(", ", missingRequiredFields)
            );
        }

        context.setDataSetDefinition(dataSet);
        context.setColumnMappings(Collections.unmodifiableMap(mappings));
        handleNext(context);
    }

    private TargetFieldDefinition findTargetField(DataSetDefinition dataSet, String sourceColumn) {
        String normalizedSourceColumn = normalizeAlias(sourceColumn);
        TargetFieldDefinition match = null;

        for (TargetFieldDefinition field : dataSet.fields()) {
            boolean matches = field.aliases().stream()
                    .map(SchemaMappingHandler::normalizeAlias)
                    .anyMatch(normalizedSourceColumn::equals);
            if (!matches) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException(
                        "Ambiguous source column '" + sourceColumn + "' for table '" + dataSet.tableName() + "'"
                );
            }
            match = field;
        }
        return match;
    }

    private static String normalizeAlias(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase().replaceAll("[\\s_\\-]+", "");
    }
}
