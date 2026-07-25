package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileReader;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

@Component
@RequiredArgsConstructor
public class TableBoundaryDetectHandler extends AbstractImportHandler {

    private final RawFileReader rawFileReader;

    @Override
    public void doImport(ImportContext context) {

        // Get object key
        String objectKey = context.getObjectKey();

        // Input data from Excel workbook
        try (
                InputStream inputStream = rawFileReader.readFile(objectKey);
                Workbook workbook = WorkbookFactory.create(inputStream);
        ){
            int indexNum = workbook.getNumberOfSheets();

            List<TableStructure> tableList = new ArrayList<>();
            for (int i = 0; i < indexNum; i++) {
                TableStructure tableStructure = detectBoundary(workbook.getSheetAt(i));
                tableList.add(tableStructure);
            }

        } catch (Exception e) {
            throw new RuntimeException("Excel table boundary detection failed for object: " + objectKey, e);
        }
    }

    private TableStructure detectBoundary(Sheet sheet) {
        String sheetName = sheet.getSheetName();

        Map<Integer, Integer> topBoundaryByColumn = new HashMap<>();
        Map<Integer, Integer> bottomBoundaryByColumn = new HashMap<>();
        Map<Integer, Integer> leftBoundaryByRow = new HashMap<>();
        Map<Integer, Integer> rightBoundaryByRow = new HashMap<>();

        DataFormatter formatter = new DataFormatter();

        for (Row row : sheet) {
            for (Cell cell : row) {
                String value = formatter.formatCellValue(cell).trim();

                if (value.isEmpty()) {
                    continue;
                }

                int rowIndex = cell.getRowIndex();
                int columnIndex = cell.getColumnIndex();

                topBoundaryByColumn.merge(columnIndex, rowIndex, Math::min);
                bottomBoundaryByColumn.merge(columnIndex, rowIndex, Math::max);
                leftBoundaryByRow.merge(rowIndex, columnIndex, Math::min);
                rightBoundaryByRow.merge(rowIndex, columnIndex, Math::max);
            }
        }

        ModeResult topBoundary = calculateMode(topBoundaryByColumn.values(), true);
        ModeResult bottomBoundary = calculateMode(bottomBoundaryByColumn.values(), false);
        ModeResult leftBoundary = calculateMode(leftBoundaryByRow.values(), true);
        ModeResult rightBoundary = calculateMode(rightBoundaryByRow.values(), false);

        double confidence = Math.min(
                Math.min(topBoundary.proportion(), bottomBoundary.proportion()),
                Math.min(leftBoundary.proportion(), rightBoundary.proportion())
        );

        return new TableStructure(
                sheetName,
                topBoundary.index(),
                leftBoundary.index(),
                rightBoundary.index(),
                bottomBoundary.index(),
                confidence
        );
    }

    private ModeResult calculateMode(Collection<Integer> boundaryIndexes, boolean preferLowerIndex) {
        if (boundaryIndexes.isEmpty()) {
            return new ModeResult(0, 0);
        }

        Map<Integer, Integer> frequencies = new HashMap<>();
        boundaryIndexes.forEach(index -> frequencies.merge(index, 1, Integer::sum));

        int modeIndex = 0;
        int modeFrequency = 0;
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            int index = entry.getKey();
            int frequency = entry.getValue();
            boolean preferredTie = frequency == modeFrequency
                    && (preferLowerIndex ? index < modeIndex : index > modeIndex);

            if (frequency > modeFrequency || preferredTie) {
                modeIndex = index;
                modeFrequency = frequency;
            }
        }

        return new ModeResult(modeIndex, (double) modeFrequency / boundaryIndexes.size());
    }

    private record ModeResult(int index, double proportion) {
    }
}
