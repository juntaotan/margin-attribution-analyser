package dev.margintrace.margin_attribution_backend.importation.model;

public record TableStructure(
        String sheetName,
        int headerRowIndex,
        int leftColumnIndex,
        int rightColumnIndex,
        int lastRowIndex,
        double confidence
) {
}
