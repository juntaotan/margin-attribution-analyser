package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import dev.margintrace.margin_attribution_backend.importation.model.ImportStatus;
import dev.margintrace.margin_attribution_backend.importation.repository.ImportJobRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * <h1>Import Job State Service</h1>
 *
 * <p>
 * Service for managing the state of import jobs. It provides methods to create new import jobs,
 * transition their states, and handle failures. And its status are defined in the ImportStatus enum,
 * which includes states such as PENDING, VALIDATING, VALIDATED, and FAILED.
 * </p>
 *
 * <ol>
 *     <li> Create a PENDING transaction and get joinId</li>
 *     <li> Search ImportJob according to joinId </li>
 *     <li> Check current status and Call entity transition method (transitionTo)</li>
 *     <li> Use independent transaction commit status. </li>
 *     <li> Use @Version to handle concurrent modifications. </li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ImportJobStateService {
    private static final String IMPORT_OBJECT_PREFIX = "imports/";

    private final ImportJobRepository importJobRepository;

    /**
     * Creates a {@code PENDING} job with a provisional object key.
     *
     * @return the saved job
     * @throws IllegalArgumentException if the file name or table name is invalid, or the mapping is not confirmed
     */
    @Transactional
    public ImportJob createPendingJob(String originalFilename, String mappingTableName, boolean mappingResult) {
        String storageObjectKey = IMPORT_OBJECT_PREFIX + UUID.randomUUID();
        return importJobRepository.save(
                ImportJob.pending(originalFilename, storageObjectKey, mappingTableName, mappingResult));
    }

    /**
     * Moves a job from the expected status to the next one, for example when a stage begins. The change is
     * committed in its own transaction so that it is visible while the import continues.
     *
     * @throws IllegalArgumentException if the job does not exist
     * @throws IllegalStateException if the job is not in the expected status or the transition is not allowed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transition(Long jobId, ImportStatus expected, ImportStatus next) {
        ImportJob job = getJobWithExpectedStatus(jobId, expected);
        job.transitionTo(next);
    }

    /**
     * Completes validation of a job in the {@code VALIDATING} status, in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeValidation(Long jobId, FileExtension fileExtension) {
        ImportJob job = getJobWithExpectedStatus(jobId, ImportStatus.VALIDATING);
        job.completeValidation(fileExtension);
    }

    /**
     * Completes raw storage of a job in the {@code STORING} status, in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRawStorage(Long jobId, String storageObjectKey) {
        ImportJob job = getJobWithExpectedStatus(jobId, ImportStatus.STORING);
        job.completeRawStorage(storageObjectKey);
    }

    /**
     * Completes a job in the {@code WRITING_TO_DATALAKE} status with its row counts, in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeWrite(Long jobId, long importedRows, long rejectedRows) {
        ImportJob job = getJobWithExpectedStatus(jobId, ImportStatus.WRITING_TO_DATALAKE);
        job.completeWrite(importedRows, rejectedRows);
    }

    /**
     * @return the job
     * @throws IllegalArgumentException if the job does not exist
     */
    @Transactional(readOnly = true)
    public ImportJob getRequiredJob(Long jobId) {
        return importJobRepository
                .findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found: " + jobId));
    }

    /**
     * @param page the zero-based page number; negative values are treated as 0
     * @param size the page size, limited to 1 to 100
     * @return the jobs, newest first
     */
    @Transactional(readOnly = true)
    public Page<ImportJob> listJobs(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return importJobRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(safePage, safeSize));
    }

    /**
     * Moves a job from the expected status to a failure status and records the error, in its own transaction.
     *
     * @throws IllegalStateException if the job is not in the expected status or the transition is not allowed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            Long jobId, ImportStatus expected, ImportStatus failureStatus, String errorCode, String errorMessage) {
        ImportJob job = getJobWithExpectedStatus(jobId, expected);

        job.transitionToFailure(failureStatus, errorCode, errorMessage);
    }

    private ImportJob getJobWithExpectedStatus(Long jobId, ImportStatus expected) {
        ImportJob job = importJobRepository
                .findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Import job not found: " + jobId));

        if (job.getStatus() != expected) {
            throw new IllegalStateException(
                    "Import job %d expected %s, but was %s".formatted(jobId, expected, job.getStatus()));
        }
        return job;
    }
}
