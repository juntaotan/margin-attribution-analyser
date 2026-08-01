package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Handles the database-writing stage of the import process
 *
 * <p> This handler reads the detected table structure and column data types from the {@link ImportContext}, creates the
 * destination PostgreSQL table, reads the source worksheet, and batch-inserts its data rows through
 * {@link JdbcTemplate}.</p>
 */
@Component
public class DatabaseWriterHandler extends AbstractImportHandler {
    private final JdbcTemplate jdbcTemplate;
    private final RawFileReader rawFileReader;

    public DatabaseWriterHandler(JdbcTemplate jdbcTemplate, RawFileReader rawFileReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.rawFileReader = rawFileReader;
    }

    /**
     * Creates a destination table and imports the detected Excel data rows.
     *
     * <p>This method performs the following operations: reading the source sheet name as the destination table name,
     * building SQL column definitions from the detected column names and data types, creating the PostgreSQL table,
     * converting cell values to the inferred types, and batch-inserting all non-empty rows.</p>
     *
     * @param context  the import context containing the detected table structure and column data types
     */
    @Override
    @Transactional
    public void doImport(ImportContext context) {
        // Get SQL statement's parameters
        // TODO: change the table name with automatic generation to avoid the risk of injection
        String tableName = context
                .getTableStructure()
                .sheetName()
                .trim()
                .toLowerCase()
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        Map<String, DataType> columnTypes = context.getColumnTypes();
        List<String> columnNames = buildColumnNames(columnTypes);
        String columnDefinition = buildColumnStatement(columnTypes, columnNames);

        // Generate SQL statement
        String sql = buildCreateTableSql(tableName, columnDefinition);

        // Create the destination table, then import all non-empty data rows from the detected Excel table.
        jdbcTemplate.execute(sql);
        insertRows(context, tableName, columnNames);
    }

    /**
     * Builds a complete SQL {@code CREATE TABLE} statement.、
     *
     * <p>The generated table contains an auto-incrementing {@code BIGINT} primary key named {@code id}, followed by the
     * supplied column definitions.</p>
     *
     * @param tableName  the name of the table to be created
     * @param definition  the SQL fragment containing the column definitions
     * @return  a complete SQL {@code CREATE TABLE} statement
     */
    String buildCreateTableSql(String tableName, String definition){
        return "CREATE TABLE " + tableName + "("
                + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + definition
                + ")";
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
    String buildColumnStatement(Map<String, DataType> columnTypes) {
        return buildColumnStatement(columnTypes, buildColumnNames(columnTypes));
    }

    private String buildColumnStatement(Map<String, DataType> columnTypes, List<String> columnNames) {
        StringJoiner definition = new StringJoiner("," + System.lineSeparator());
        int columnIndex = 0;
        for (Map.Entry<String, DataType> entry:columnTypes.entrySet()) {
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
            definition.add(columnNames.get(columnIndex++) + " " + columnType);
        }
        return definition.toString();
    }

    private List<String> buildColumnNames(Map<String, DataType> columnTypes) {
        List<String> columnNames = new ArrayList<>(columnTypes.size());
        int errorColumnNum = 0;
        for (String originalName : columnTypes.keySet()) {
            String normalizedColumnName = normalizeColumnName(originalName, errorColumnNum);
            if (normalizedColumnName.contains("unnamed column")) {
                errorColumnNum += 1;
            }
            columnNames.add(normalizedColumnName);
        }
        return columnNames;
    }

    private void insertRows(ImportContext context, String tableName, List<String> columnNames) {
        TableStructure tableStructure = context.getTableStructure();

        try (
                InputStream inputStream = rawFileReader.readFile(context.getObjectKey());
                Workbook workbook = WorkbookFactory.create(inputStream)
        ) {
            Sheet sheet = workbook.getSheet(tableStructure.sheetName());
            if (sheet == null) {
                throw new IllegalArgumentException("Worksheet does not exist: " + tableStructure.sheetName());
            }

            List<Object[]> rows = readRows(sheet, tableStructure, context.getColumnTypes());
            if (!rows.isEmpty()) {
                jdbcTemplate.batchUpdate(buildInsertSql(tableName, columnNames), rows);
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel data insertion failed for object: " + context.getObjectKey(), e);
        }
    }

    private List<Object[]> readRows(
            Sheet sheet,
            TableStructure tableStructure,
            Map<String, DataType> columnTypes
    ) {
        List<Object[]> rows = new ArrayList<>();
        List<DataType> types = new ArrayList<>(columnTypes.values());
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        for (int rowIndex = tableStructure.headerRowIndex() + 1;
             rowIndex <= tableStructure.lastRowIndex();
             rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Object[] values = new Object[types.size()];
            boolean hasValue = false;

            for (int offset = 0; offset < types.size(); offset++) {
                int columnIndex = tableStructure.leftColumnIndex() + offset;
                Cell cell = row == null
                        ? null
                        : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                values[offset] = convertCellValue(cell, types.get(offset), formatter, evaluator);
                hasValue |= values[offset] != null;
            }

            if (hasValue) {
                rows.add(values);
            }
        }
        return rows;
    }

    private Object convertCellValue(
            Cell cell,
            DataType targetType,
            DataFormatter formatter,
            FormulaEvaluator evaluator
    ) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }

        return switch (targetType) {
            case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> cell.getBooleanCellValue();
            case DATE -> excelDateTime(cell).toLocalDate();
            case TIME -> excelDateTime(cell).toLocalTime();
            case TIMESTAMP -> excelDateTime(cell);
            case TEXT, UNKNOWN -> formatter.formatCellValue(cell, evaluator);
        };
    }

    private LocalDateTime excelDateTime(Cell cell) {
        if (!DateUtil.isCellDateFormatted(cell)) {
            throw new IllegalArgumentException("Cell " + cell.getAddress() + " is not formatted as a date or time");
        }
        return DateUtil.getLocalDateTime(cell.getNumericCellValue());
    }

    private String buildInsertSql(String tableName, List<String> columnNames) {
        StringJoiner columns = new StringJoiner(",");
        StringJoiner placeholders = new StringJoiner(",");
        columnNames.forEach(columnName -> {
            columns.add(columnName);
            placeholders.add("?");
        });
        return "INSERT INTO " + tableName + "(" + columns + ") VALUES (" + placeholders + ")";
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
