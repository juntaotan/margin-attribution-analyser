package dev.margintrace.margin_attribution_backend.importation.model;

import java.math.BigInteger;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "import_job")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ImportJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_extension", length = 20)
    private FileExtension fileExtension;

    @Column(name = "storage_object_key", length = 500)
    private String storageObjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private ImportStatus status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String  errorMessage;

    @Column(name = "imported_rows", nullable = false)
    private long importedRows;

    @Column(name = "rejected_rows", nullable = false)
    private long rejectedRows;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static ImportJob createPending(String originalFilename, FileExtension fileExtension, String storageObjectKey) {

    if (originalFilename == null || originalFilename.isBlank()) {
        throw new IllegalArgumentException("Original filename must not be blank");
    }

    if (fileExtension == null) {
        throw new IllegalArgumentException("File extension must not be null");
    }

    if (storageObjectKey == null || storageObjectKey.isBlank()) {
        throw new IllegalArgumentException("Storage object key must not be blank");
    }

    ImportJob job = new ImportJob();
    job.originalFilename = originalFilename;
    job.fileExtension = fileExtension;
    job.storageObjectKey = storageObjectKey;
    job.status = ImportStatus.PENDING;
    job.importedRows = 0L;
    job.rejectedRows = 0L;

    return job;
}
}
