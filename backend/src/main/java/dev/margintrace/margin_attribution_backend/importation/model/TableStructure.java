package dev.margintrace.margin_attribution_backend.importation.model;

/**
 * Location of the data table in a worksheet, as zero-based row and column indexes, with the share of rows and
 * columns that agree with the detected edges ({@code confidence}, 0 to 1).
 */
public record TableStructure(
        String sheetName,
        int headerRowIndex,
        int leftColumnIndex,
        int rightColumnIndex,
        int lastRowIndex,
        double confidence) {}
