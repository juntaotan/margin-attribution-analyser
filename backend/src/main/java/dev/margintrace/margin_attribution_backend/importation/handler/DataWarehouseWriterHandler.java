package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps the data written to the database in the previous step {@link DatabaseWriterHandler} to the configured schema and
 * stores the mapped data in the data warehouse.
 */
@Component
public class DataWarehouseWriterHandler extends AbstractImportHandler {
    private final JdbcTemplate jdbcTemplate;

    public DataWarehouseWriterHandler(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Copies mapped columns from the raw database table into the configured warehouse table.
     *
     * <p>Only source columns present in {@link ImportContext#getColumnMappings()} are selected. Columns without a schema
     * mapping remain in the raw table and are not written to the warehouse.</p>
     *
     * @param context import context containing the raw table metadata and schema-mapping result
     */
    @Override
    @Transactional
    public void doImport(ImportContext context) {
        validateContext(context);

        List<MappedDatabaseColumn> mappedColumns = buildMappedColumns(context);
        if (mappedColumns.isEmpty()) {
            context.setWarehouseImportedRows(0);
            handleNext(context);
            return;
        }

        String sql = buildInsertSelectSql(
                context.getRawTableName(),
                context.getDataSetDefinition().tableName(),
                mappedColumns
        );
        context.setWarehouseImportedRows(jdbcTemplate.update(sql));
        handleNext(context);
    }

    private List<MappedDatabaseColumn> buildMappedColumns(ImportContext context) {
        List<MappedDatabaseColumn> mappedColumns = new ArrayList<>();
        for (Map.Entry<String, TargetFieldDefinition> mapping : context.getColumnMappings().entrySet()) {
            String rawColumnName = context.getRawColumnNames().get(mapping.getKey());
            if (rawColumnName == null) {
                throw new IllegalArgumentException("Raw database column is missing for source column: "
                        + mapping.getKey());
            }
            mappedColumns.add(new MappedDatabaseColumn(rawColumnName, mapping.getValue()));
        }
        return mappedColumns;
    }

    String buildInsertSelectSql(
            String rawTableName,
            String warehouseTableName,
            List<MappedDatabaseColumn> mappedColumns
    ) {
        String targetColumns = mappedColumns.stream()
                .map(column -> column.targetField().columnName())
                .collect(Collectors.joining(","));
        String sourceColumns = mappedColumns.stream()
                .map(column -> castExpression(column.rawColumnName(), column.targetField().dataType()))
                .collect(Collectors.joining(","));

        return "INSERT INTO " + warehouseTableName + "(" + targetColumns + ") SELECT "
                + sourceColumns + " FROM " + rawTableName;
    }

    private String castExpression(String rawColumnName, DataType targetType) {
        return switch (targetType) {
            case NUMERIC -> "CAST(" + rawColumnName + " AS NUMERIC(18,6))";
            case BOOLEAN -> "CAST(" + rawColumnName + " AS BOOLEAN)";
            case DATE -> "CAST(" + rawColumnName + " AS DATE)";
            case TIME -> "CAST(" + rawColumnName + " AS TIME)";
            case TIMESTAMP -> "CAST(" + rawColumnName + " AS TIMESTAMP)";
            case TEXT, UNKNOWN -> "CAST(" + rawColumnName + " AS VARCHAR)";
        };
    }

    private void validateContext(ImportContext context) {
        List<String> missingValues = new ArrayList<>();
        if (context == null) {
            throw new IllegalArgumentException("Import context is required for warehouse writing");
        }
        if (context.getRawTableName() == null || context.getRawTableName().isBlank()) {
            missingValues.add("rawTableName");
        }
        if (context.getRawColumnNames() == null || context.getRawColumnNames().isEmpty()) {
            missingValues.add("rawColumnNames");
        }
        if (context.getDataSetDefinition() == null) {
            missingValues.add("dataSetDefinition");
        }
        if (context.getColumnMappings() == null) {
            missingValues.add("columnMappings");
        }
        if (!missingValues.isEmpty()) {
            throw new IllegalArgumentException("Missing warehouse writing context: "
                    + String.join(", ", missingValues));
        }
    }

    record MappedDatabaseColumn(String rawColumnName, TargetFieldDefinition targetField) {
    }
}
