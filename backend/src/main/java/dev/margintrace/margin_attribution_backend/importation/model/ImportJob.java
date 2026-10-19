package dev.margintrace.margin_attribution_backend.importation.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An import job and its status. Status changes are only allowed along the transitions defined by
 * {@link ImportStatus#canTransitionTo(ImportStatus)}; reaching a terminal status records the completion time.
 * Optimistic locking prevents concurrent updates from overwriting each other.
 */
@Entity
@Table(name = "import_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "mapping_table_name", nullable = false, length = 100)
    private String mappingTableName;

    @Column(name = "mapping_result", nullable = false)
    private boolean mappingResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_extension", length = 20)
    private FileExtension fileExtension;

    @Column(name = "storage_object_key", nullable = false, length = 500)
    private String storageObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ImportStatus status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "imported_rows", nullable = false)
    private long importedRows;

    @Column(name = "rejected_rows", nullable = false)
    private long rejectedRows;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates a job in the {@code PENDING} status.
     *
     * @param originalFilename the uploaded file name
     * @param storageObjectKey the provisional object key
     * @param mappingTableName the confirmed target table, at most 100 characters
     * @param mappingResult whether the user confirmed the mapping; must be true
     * @return the new job
     * @throws IllegalArgumentException if any value is missing or invalid, or the mapping is not confirmed
     */
    public static ImportJob pending(
            String originalFilename, String storageObjectKey, String mappingTableName, boolean mappingResult) {
        if (mappingTableName == null || mappingTableName.isBlank() || mappingTableName.length() > 100) {
            throw new IllegalArgumentException("A valid mapping table name is required");
        }
        if (!mappingResult) {
            throw new IllegalArgumentException("Mapping must be confirmed before importing");
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("Original filename must not be blank");
        }
        if (storageObjectKey == null || storageObjectKey.isBlank()) {
            throw new IllegalArgumentException("Storage object key must not be blank");
        }

        ImportJob job = new ImportJob();
        job.originalFilename = originalFilename;
        job.mappingTableName = mappingTableName;
        job.mappingResult = mappingResult;
        job.storageObjectKey = storageObjectKey;
        job.status = ImportStatus.PENDING;
        job.importedRows = 0L;
        job.rejectedRows = 0L;
        return job;
    }

    /**
     * Moves the job to the next status and clears any error.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionTo(ImportStatus next) {
        transitionTo(next, null, null);
    }

    /**
     * Moves the job to a failure status and records the error, with the message truncated to 1,000 characters.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public void transitionToFailure(ImportStatus failureStatus, String errorCode, String errorMessage) {
        transitionTo(failureStatus, errorCode, errorMessage);
    }

    /**
     * Marks validation as successful and records the file type.
     *
     * @throws IllegalArgumentException if the file type is missing
     * @throws IllegalStateException if the job is not being validated
     */
    public void completeValidation(FileExtension fileExtension) {
        if (fileExtension == null) {
            throw new IllegalArgumentException("File extension must not be null");
        }

        transitionTo(ImportStatus.VALIDATED);
        this.fileExtension = fileExtension;
    }

    /**
     * Marks the raw file as stored and records its object key.
     *
     * @throws IllegalArgumentException if the key is blank or longer than 500 characters
     * @throws IllegalStateException if the file is not being stored
     */
    public void completeRawStorage(String storageObjectKey) {
        if (storageObjectKey == null || storageObjectKey.isBlank()) {
            throw new IllegalArgumentException("Storage object key must not be blank");
        }
        if (storageObjectKey.length() > 500) {
            throw new IllegalArgumentException("Storage object key must not exceed 500 characters");
        }

        transitionTo(ImportStatus.STORED);
        this.storageObjectKey = storageObjectKey;
    }

    /**
     * Marks the import as successful and records the row counts.
     *
     * @throws IllegalArgumentException if a count is negative
     * @throws IllegalStateException if the job is not being written
     */
    public void completeWrite(long importedRows, long rejectedRows) {
        if (importedRows < 0 || rejectedRows < 0) {
            throw new IllegalArgumentException("Imported and rejected row counts must not be negative");
        }
        transitionTo(ImportStatus.WRITE_SUCCESS);
        this.importedRows = importedRows;
        this.rejectedRows = rejectedRows;
    }

    private void transitionTo(ImportStatus next, String errorCode, String errorMessage) {
        if (next == null) {
            throw new IllegalArgumentException("Next status must not be null");
        }
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal import status transition: %s -> %s".formatted(status, next));
        }

        status = next;
        updatedAt = Instant.now();

        if (next.isTerminal()) {
            completedAt = updatedAt;
        }

        this.errorCode = errorCode;
        this.errorMessage = truncate(errorMessage);
    }

    private String truncate(String value) {
        int maximumLength = 1000;
        if (value == null || value.length() <= maximumLength) {
            return value;
        }

        return value.substring(0, maximumLength);
    }
}
