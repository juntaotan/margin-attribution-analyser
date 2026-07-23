package dev.margintrace.margin_attribution_backend.importation.model;

public enum ImportStatus {
    VALIDATING,
    DETECTING_BOUNDARY,
    IDENTIFYING_FIELD_NAME,
    INFERRING_DATA_TYPE,
    WRITING_INTO_DB,
    PENDING,
    COMPLETED,
    CANCELLED
}
