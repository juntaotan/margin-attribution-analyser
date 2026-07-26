package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Infer the logical data type of each column within a previously detected Excel table.
 *
 * <p> The handler reads the source workbook from raw object storage, inspects all non-header cells within the table
 * boundaries, and determines the dominant {@link DataType} for each column. </p>
 *
 * <p> Blank cells, error cells, and cells whose types cannot be classified are ignored during type inference. When no
 * reliable type can be selected, the column is classified as {@link DataType#UNKNOWN}. After stream process finished,
 * this error will be provided to user to select whether change it into {@Link DataType#UNKNOWN} </p>
 */
@Component
@RequiredArgsConstructor
public class ColumnTypeInferHandler extends AbstractImportHandler {

    private final RawFileReader rawFileReader;

    /**
     * Identifies the data type of every column and stores the resulting column-type mapping in the import context.
     *
     * <p> This handler requires the context to contain: </p>
     * <ul>
     *     <li> a valid storage object key; </li>
     *     <li> a previously detected {@link TableStructure}. </li>
     * </ul>
     *
     * <p> After successful identification, the next handler in the import chain is invoked.</p>
     *
     * @param context current import context containing the source object key and detected table structure
     * @throws NullPointerException if {@code context} is {@code null}
     * @throws IllegalArgumentException if the object key is blank, the table structure is missing, or the target sheet
     *                                  cannot be found
     * @throws RuntimeException if the workbook cannot be read or column type identification fails
     */
    @Override
    public void doImport(ImportContext context) {

        // Get object key to start the task and get the table structure
        String objectKey = context.getObjectKey();
        TableStructure tableStructure = context.getTableStructure();

        try (
                InputStream inputStream = rawFileReader.readFile(objectKey);
                Workbook workbook = WorkbookFactory.create(inputStream)
        ) {
            Sheet sheet = workbook.getSheet(tableStructure.sheetName());
            context.setColumnTypes(inferColumnTypes(sheet, tableStructure));
        } catch (Exception e) {
            throw new RuntimeException("Excel column type identification failed for object: " + objectKey, e);
        }

        // Continue next import step
        handleNext(context);
    }

    /**
     * Infers the logical data type of each column within the detected table boundaries.
     *
     * The method iterates from the leftmost column to the rightmost column defined by {@link TableStructure}. Each
     * column is analysed independently by {@link #identifyColumnType(Sheet, TableStructure, int)}.
     *
     * @param sheet  the worksheet containing the table to be imported
     * @param tableStructure  the detected table structure containing the column boundaries and row boundaries
     * @return  a mapping from zero-based Excel column indexes to their inferred data types
     */
    private Map<Integer, DataType> inferColumnTypes(Sheet sheet, TableStructure tableStructure) {

        // Collect the columtype with the format: index + data type
        Map<Integer, DataType> columnTypes = new LinkedHashMap<>();

        // Iterates from the leftmost column to the rightmost column
        int leftColumnIndex = tableStructure.leftColumnIndex();
        int rightColumnIndex = tableStructure.rightColumnIndex();

        for (int columnIndex = leftColumnIndex; columnIndex <= rightColumnIndex; columnIndex++) {
            columnTypes.put(columnIndex, identifyColumnType(sheet, tableStructure, columnIndex));
        }

        return columnTypes;
    }

    /**
     * Infer column types for selected column according to top 100 rows
     *
     * The method iterates from the first column in the selected column that is sent by
     * {@Link #inferColumnTypes(sheet, TableStructure tableStructure)}
     *
     * @param sheet  the worksheet containing the table to be imported
     * @param tableStructure  the detected table structure containing the column boundaries and row boundaries
     * @param columnIndex the column selected by {@Link #inferColumnTypes(sheet, TableStructure tableStructure)}
     * @return type of selected column
     */
    private DataType identifyColumnType(Sheet sheet, TableStructure tableStructure, int columnIndex) {
        Map<DataType, Integer> frequencies = new EnumMap<>(DataType.class);

        int headerRowIndex = tableStructure.headerRowIndex();
        int lastRowIndex = tableStructure.lastRowIndex();
        for (int rowIndex = headerRowIndex; rowIndex <= lastRowIndex; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            Cell cell = row == null ? null : row.getCell(columnIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            DataType dataType = identifyCellType(cell);

            if (dataType != DataType.UNKNOWN) {
                frequencies.merge(dataType, 1, Integer::sum);
            }
        }

        return selectColumnType(frequencies);

    }

    private DataType identifyCellType(Cell cell) {
        if (cell == null) {
            return DataType.UNKNOWN;
        }

        CellType cellType = cell.getCellType();
        if (cellType == CellType.FORMULA) {
            cellType = cell.getCachedFormulaResultType();
        }

        return switch (cellType) {
            case NUMERIC -> identifyNumericType(cell);
            case STRING -> cell.getStringCellValue().isBlank()
                    ? DataType.UNKNOWN
                    : DataType.TEXT;
            case BOOLEAN -> DataType.BOOLEAN;
            case BLANK, ERROR, _NONE, FORMULA -> DataType.UNKNOWN;
        };
    }

    private DataType identifyNumericType(Cell cell) {
        if (!DateUtil.isCellDateFormatted(cell)) {
            return DataType.NUMERIC;
        }

        TemporalFormatParts formatParts = inspectTemporalFormat(
                cell.getCellStyle().getDataFormatString()
        );
        if (formatParts.hasDate() && formatParts.hasTime()) {
            return DataType.TIMESTAMP;
        }
        if (formatParts.hasTime()) {
            return DataType.TIME;
        }
        if (formatParts.hasDate()) {
            return DataType.DATE;
        }

        // Custom or locale-specific formats may be recognized by POI as dates even when their tokens cannot be
        // interpreted here. Use the serial value as a conservative fallback: a fraction of a day is a time component.
        double numericValue = cell.getNumericCellValue();
        if (numericValue >= 0 && numericValue < 1) {
            return DataType.TIME;
        }
        return numericValue == Math.rint(numericValue)
                ? DataType.DATE
                : DataType.TIMESTAMP;
    }

    private TemporalFormatParts inspectTemporalFormat(String dataFormat) {
        if (dataFormat == null || dataFormat.isBlank()) {
            return new TemporalFormatParts(false, false);
        }

        StringBuilder normalized = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < dataFormat.length(); index++) {
            char current = Character.toLowerCase(dataFormat.charAt(index));

            if (current == '"') {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == '\\' || current == '_' || current == '*') {
                index++;
                continue;
            }
            if (current == '[') {
                int closingBracket = dataFormat.indexOf(']', index + 1);
                if (closingBracket < 0) {
                    break;
                }
                String bracketContent = dataFormat
                        .substring(index + 1, closingBracket)
                        .toLowerCase();
                if (bracketContent.matches("[hms]+")) {
                    normalized.append(bracketContent);
                }
                index = closingBracket;
                continue;
            }

            normalized.append(current);
        }

        String format = normalized.toString();
        boolean hasExplicitDate = format.indexOf('y') >= 0 || format.indexOf('d') >= 0;
        boolean hasExplicitTime = format.indexOf('h') >= 0
                || format.indexOf('s') >= 0
                || format.contains("am/pm")
                || format.contains("a/p");
        boolean hasMonthOrMinute = format.indexOf('m') >= 0;

        boolean hasTime = hasExplicitTime
                || hasMonthOrMinute && format.indexOf(':') >= 0;
        boolean hasDate = hasExplicitDate
                || hasMonthOrMinute && !hasTime;

        return new TemporalFormatParts(hasDate, hasTime);
    }

    private DataType selectColumnType(Map<DataType, Integer> frequencies) {
        if (frequencies.isEmpty()) {
            return DataType.UNKNOWN;
        }

        if (frequencies.keySet().stream().allMatch(this::isTemporalType)
                && frequencies.size() > 1) {
            return DataType.TIMESTAMP;
        }

        int highestFrequency = frequencies.values().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);

        DataType selectedType = DataType.UNKNOWN;
        int typesWithHighestFrequency = 0;
        for (Map.Entry<DataType, Integer> entry : frequencies.entrySet()) {
            if (entry.getValue() == highestFrequency) {
                selectedType = entry.getKey();
                typesWithHighestFrequency++;
            }
        }

        return typesWithHighestFrequency == 1 ? selectedType : DataType.UNKNOWN;
    }

    private boolean isTemporalType(DataType dataType) {
        return dataType == DataType.DATE
                || dataType == DataType.TIME
                || dataType == DataType.TIMESTAMP;
    }

    private record TemporalFormatParts(boolean hasDate, boolean hasTime) {
    }
}
