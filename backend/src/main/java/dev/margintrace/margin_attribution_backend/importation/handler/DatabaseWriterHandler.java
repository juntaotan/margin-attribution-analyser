package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;

import java.util.Map;

public class DatabaseWriterHandler extends AbstractImportHandler {
    @Override
    public void doImport(ImportContext context) {

    }

    private String buildCreateTableSql(String tableName, String sql){
        return "CREATE TABLE " + tableName + "(" + sql + ")";
    }

    /**
     * Builds the SQL column-definition fragment from the detected column names and data types.
     *
     * <p> Each map entry represents one database column: </p>
     * <ul>
     *     <li>The key is the original column name.</li>
     *     <li>The value is the detected {@link DataType}.</li>
     * </ul>
     *
     * <p> Column names should be normalized before being added to the SQL statement. Invalid or empty column names are
     * replaced with generated names. Each {@link DataType} is converted to its corresponding PostgreSQL data type.</p>
     *
     * @param columnTypes  a map containing original column names and their detected data types
     * @return  an SQL fragment containing normalized column names and their corresponding PostgreSQL data types
     */
    private String buildColumnStatement(Map<String, DataType> columnTypes) {
        StringBuilder sql = new StringBuilder();
        int errorColumnNum = 0;
        for (Map.Entry<String, DataType> entry:columnTypes.entrySet()) {
            // Identify column name and count the number of columns with error name
            String normalizedColumnName = normalizeColumnName(entry.getKey(),errorColumnNum);
            if (normalizedColumnName.contains("unnamed column")) {
                errorColumnNum += 1;
            }

            // Identify column type according to columnTypes
            DataType type = entry.getValue();
            String columnType = switch (type) {
                case NUMERIC -> "NUMERIC(20,5)";
                case BOOLEAN -> "BOOLEAN";
                case DATE -> "DATE";
                case TIME -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
                case TEXT, UNKNOWN -> "TEXT";
            };
            // Generate final SQL statement
            sql.append(normalizedColumnName).append(" ").append(columnType).append(",").append(System.lineSeparator());
        }
        return sql.toString();
    }

    /**
     * Normalizes an original column name so that it can be used as a database column identifier
     *
     * <p>The normalization process includes: removing leading and trailing whitespace, converting all letters to
     * lowercase, replacing whitespace characters and unsupported characters with underscores and removing leading and
     * trailing underscores.</p>
     *
     * <p> Specially, adding the prefix {@code col_} when the name starts with a digit.</p>
     *
     * @param originalName  the original column name read from the source file
     * @param errorColumnNum  the sequence number used to distinguish invalid or unnamed columns
     * @return  the normalized database column name
     */
    private String normalizeColumnName(String originalName, int errorColumnNum) {
        StringBuilder normalizedName = new StringBuilder();
        if (originalName == null || originalName.isBlank()) {
            return "unnamed column";
        }
        String normalized = originalName
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");

        // If normalised name has empty that all contents are special characteristics, return error column name
        if (normalized.isBlank()) {
            normalized = "unnamed_column";
        }

        if (Character.isDigit(normalized.charAt(0))) {
            normalized = "col_" + normalized;
        }

        normalizedName.append(normalized).append("_").append(errorColumnNum);

        return normalizedName.toString();
    }
}
