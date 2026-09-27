package dev.margintrace.margin_attribution_backend.importation.controller;

import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import dev.margintrace.margin_attribution_backend.importation.model.ImportStatus;
import java.time.Instant;

public record ImportJobResponse(
        Long jobId, String filename, String targetTable, ImportStatus status,
        String stage, int progress, long importedRows, long rejectedRows,
        String errorCode, String errorMessage, Instant createdAt, Instant completedAt
) {
    public static ImportJobResponse from(ImportJob job) {
        return new ImportJobResponse(
                job.getId(), job.getOriginalFilename(), job.getMappingTableName(), job.getStatus(),
                stage(job.getStatus()), progress(job.getStatus()), job.getImportedRows(), job.getRejectedRows(),
                job.getErrorCode(), job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt()
        );
    }

    private static String stage(ImportStatus status) {
        return switch (status) {
            case PENDING, VALIDATING -> "VALIDATING";
            case VALIDATED, STORING -> "STORING_RAW_FILE";
            case STORED -> "QUEUED";
            case TRANSFORMING, TRANSFORMED -> "TRANSFORMING";
            case WRITING_TO_DATALAKE -> "WRITING_DATABASES";
            case WRITE_SUCCESS -> "COMPLETED";
            case VALIDATING_FAILED, STORING_FAILED, TRANSFORMING_FAILED, WRITE_FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    private static int progress(ImportStatus status) {
        return switch (status) {
            case PENDING -> 5;
            case VALIDATING -> 10;
            case VALIDATED -> 20;
            case STORING -> 30;
            case STORED -> 40;
            case TRANSFORMING -> 55;
            case TRANSFORMED -> 70;
            case WRITING_TO_DATALAKE -> 85;
            case WRITE_SUCCESS -> 100;
            case VALIDATING_FAILED, STORING_FAILED, TRANSFORMING_FAILED, WRITE_FAILED, CANCELLED -> 100;
        };
    }
}
