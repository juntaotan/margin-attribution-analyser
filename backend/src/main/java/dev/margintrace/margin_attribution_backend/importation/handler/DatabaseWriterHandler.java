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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Creates a database table from the detected Excel structure and imports the source rows without warehouse mapping.
 *
 * <p> The handler reads the table storing in the data pool and write it into database corresponding table according to
 * its {@link DataType} inferred in {@link ColumnTypeInferHandler}</p>
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
     * Write the data into database according to its table name
     *
     * <p> This handler requires the context to contain: </p>
     * <ul>
     *     <li> a sheet name;</li>
     *     <li> each column type;</li>
     *     <li> and its data.</li>
     * </ul>
     *
     * @param context current import context containing the table name and its data
     */
    @Override
    @Transactional
    public void doImport(ImportContext context) {
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

        jdbcTemplate.execute(buildCreateTableSql(tableName, columnDefinition));
        insertRows(context, tableName, columnNames);
    }

    /**
     * Generate SQL statement according to its table name and each column definition
     *
     * @param tableName the table name directing the table to be imported
     * @param definition pairs of column name and its data type
     * @return a SQL statement to be executed
     */
    String buildCreateTableSql(String tableName, String definition) {
        return "CREATE TABLE " + tableName + "("
                + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,"
                + definition
                + ")";
    }

    /**
     * Builds a column-definition SQL fragment directly from the detected source columns.
     *
     * <p>This package-private convenience overload gives unit tests a focused entry point for verifying column-name
     * normalization, data-type conversion, column ordering, and comma placement without creating a database table.
     * It generates the normalized database column names internally and delegates the actual SQL construction to the
     * private two-argument overload.</p>
     *
     * @param columnTypes source column names and their inferred logical data types, in worksheet column order
     * @return SQL column definitions suitable for inclusion in a {@code CREATE TABLE} statement
     */
    String buildColumnStatement(Map<String, DataType> columnTypes) {
        return buildColumnStatement(columnTypes, buildColumnNames(columnTypes));
    }

    /**
     * Builds the SQL column definitions using a previously generated list of database column names.
     *
     * <p>This is the implementation used by the import workflow. It converts each inferred {@link DataType} to its
     * PostgreSQL type and pairs it with the database column name at the same position. Reusing the supplied names
     * ensures that the subsequent {@code INSERT} statement addresses exactly the columns created by the
     * {@code CREATE TABLE} statement.</p>
     *
     * @param columnTypes source columns and their inferred logical types, in worksheet column order
     * @param columnNames normalized database column names corresponding positionally to {@code columnTypes}
     * @return comma-separated SQL column definitions suitable for a {@code CREATE TABLE} statement
     */
    private String buildColumnStatement(Map<String, DataType> columnTypes, List<String> columnNames) {
        StringJoiner definition = new StringJoiner("," + System.lineSeparator());
        int columnIndex = 0;
        for (Map.Entry<String, DataType> entry : columnTypes.entrySet()) {
            String columnType = switch (entry.getValue()) {
                case NUMERIC -> "NUMERIC(20,5)";
                case BOOLEAN -> "BOOLEAN";
                case DATE -> "DATE";
                case TIME -> "TIME";
                case TIMESTAMP -> "TIMESTAMP";
                case TEXT, UNKNOWN -> "TEXT";
            };
            definition.add(columnNames.get(columnIndex++) + " " + columnType);
        }
        return definition.toString();
    }

    /**
     * Reads the detected worksheet from raw object storage and inserts its non-empty data rows into the created table.
     *
     * <p>The method reopens the workbook using the object key in {@code context}, selects the worksheet recorded in the
     * detected {@link TableStructure}, converts its cells according to the inferred source-column types, and performs a
     * batch insert using the same normalized column names that were used when creating the table.</p>
     *
     * <p>If the worksheet cannot be found, read, converted, or written, the underlying exception is wrapped in a
     * {@link RuntimeException} containing the source object key. Because {@link #doImport(ImportContext)} is
     * transactional, a failure causes the table creation and inserted rows to be rolled back together.</p>
     *
     * @param context import context containing the raw object key, detected table structure, and inferred column types
     * @param tableName database table into which the source rows will be inserted
     * @param columnNames normalized database columns in the same order as the detected worksheet columns
     */
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

    private List<String> buildColumnNames(Map<String, DataType> columnTypes) {
        List<String> columnNames = new ArrayList<>(columnTypes.size());
        int errorColumnNum = 0;
        for (String originalName : columnTypes.keySet()) {
            String normalizedColumnName = normalizeColumnName(originalName, errorColumnNum);
            if (normalizedColumnName.contains("unnamed column")) {
                errorColumnNum++;
            }
            columnNames.add(normalizedColumnName);
        }
        return columnNames;
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

    private String normalizeColumnName(String originalName, int errorColumnNum) {
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

        if (normalized.isBlank()) {
            normalized = "unnamed_column";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            normalized = "col_" + normalized;
        }
        return normalized + "_" + errorColumnNum;
    }
}
