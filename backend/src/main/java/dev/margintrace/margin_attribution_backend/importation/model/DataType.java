package dev.margintrace.margin_attribution_backend.importation.model;

/**
 * Column data types inferred from spreadsheet cells.
 */
public enum DataType {
    NUMERIC,
    TEXT,
    BOOLEAN,
    DATE,
    TIME,
    TIMESTAMP,
    UNKNOWN
}
