package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
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
import java.util.StringJoiner;

/**
 * Handles the database-writing stage of the import process
 *
 * <p>This handler reads the schema mapping produced for the imported worksheet and batch-inserts mapped values into
 * an existing canonical warehouse table through {@link JdbcTemplate}. Unmapped source columns are ignored.</p>
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
     * Imports the detected Excel rows into the existing mapped warehouse table.
     *
     * @param context  the import context containing the detected table structure and column data types
     */
    @Override
    @Transactional
    public void doImport(ImportContext context) {
        if (context == null || context.getDataSetDefinition() == null) {
            throw new IllegalArgumentException("Warehouse data-set mapping is required before database writing");
        }
        if (context.getColumnMappings() == null || context.getColumnMappings().isEmpty()) {
            throw new IllegalArgumentException("Warehouse column mappings are required before database writing");
        }

        String tableName = context.getDataSetDefinition().tableName();
        List<MappedColumn> mappedColumns = buildMappedColumns(context);
        insertRows(context, tableName, mappedColumns);
    }

    private List<MappedColumn> buildMappedColumns(ImportContext context) {
        List<MappedColumn> mappedColumns = new ArrayList<>();
        int sourceOffset = 0;
        for (String sourceColumn : context.getColumnTypes().keySet()) {
            TargetFieldDefinition targetField = context.getColumnMappings().get(sourceColumn);
            if (targetField != null) {
                mappedColumns.add(new MappedColumn(sourceOffset, targetField));
            }
            sourceOffset++;
        }
        return mappedColumns;
    }

    private void insertRows(ImportContext context, String tableName, List<MappedColumn> mappedColumns) {
        TableStructure tableStructure = context.getTableStructure();

        try (
                InputStream inputStream = rawFileReader.readFile(context.getObjectKey());
                Workbook workbook = WorkbookFactory.create(inputStream)
        ) {
            Sheet sheet = workbook.getSheet(tableStructure.sheetName());
            if (sheet == null) {
                throw new IllegalArgumentException("Worksheet does not exist: " + tableStructure.sheetName());
            }

            List<Object[]> rows = readRows(sheet, tableStructure, mappedColumns);
            if (!rows.isEmpty()) {
                List<String> targetColumns = mappedColumns.stream()
                        .map(mappedColumn -> mappedColumn.targetField().columnName())
                        .toList();
                jdbcTemplate.batchUpdate(buildInsertSql(tableName, targetColumns), rows);
            }
        } catch (Exception e) {
            throw new RuntimeException("Excel data insertion failed for object: " + context.getObjectKey(), e);
        }
    }

    private List<Object[]> readRows(
            Sheet sheet,
            TableStructure tableStructure,
            List<MappedColumn> mappedColumns
    ) {
        List<Object[]> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        for (int rowIndex = tableStructure.headerRowIndex() + 1;
             rowIndex <= tableStructure.lastRowIndex();
             rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Object[] values = new Object[mappedColumns.size()];
            boolean hasValue = false;
            List<String> missingRequiredFields = new ArrayList<>();

            for (int targetIndex = 0; targetIndex < mappedColumns.size(); targetIndex++) {
                MappedColumn mappedColumn = mappedColumns.get(targetIndex);
                int columnIndex = tableStructure.leftColumnIndex() + mappedColumn.sourceOffset();
                Cell cell = row == null
                        ? null
                        : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                Object value = convertCellValue(
                        cell,
                        mappedColumn.targetField().dataType(),
                        formatter,
                        evaluator
                );
                if (value == null && mappedColumn.targetField().required()) {
                    missingRequiredFields.add(mappedColumn.targetField().fieldKey());
                }
                values[targetIndex] = value;
                hasValue |= value != null;
            }

            if (hasValue) {
                if (!missingRequiredFields.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Required values are blank at worksheet row " + (rowIndex + 1) + ": "
                                    + String.join(", ", missingRequiredFields)
                    );
                }
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
            case NUMERIC -> numericValue(cell, formatter, evaluator);
            case BOOLEAN -> booleanValue(cell, formatter, evaluator);
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

    private BigDecimal numericValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        String value = formatter.formatCellValue(cell, evaluator).trim().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Cell " + cell.getAddress() + " cannot be converted to a numeric value: " + value,
                    exception
            );
        }
    }

    private Boolean booleanValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.BOOLEAN) {
            return cell.getBooleanCellValue();
        }
        String value = formatter.formatCellValue(cell, evaluator).trim();
        if (value.equalsIgnoreCase("true") || value.equals("1") || value.equals("是")) {
            return true;
        }
        if (value.equalsIgnoreCase("false") || value.equals("0") || value.equals("否")) {
            return false;
        }
        throw new IllegalArgumentException(
                "Cell " + cell.getAddress() + " cannot be converted to a boolean value: " + value
        );
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

    private record MappedColumn(int sourceOffset, TargetFieldDefinition targetField) {
    }
}
