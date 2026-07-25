package dev.margintrace.margin_attribution_backend.importation.service;

import java.util.UUID;

import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import dev.margintrace.margin_attribution_backend.importation.model.ImportStatus;
import dev.margintrace.margin_attribution_backend.importation.repository.ImportJobRepository;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
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

    @Transactional
    public ImportJob createPendingJob(String originalFilename) {
        String storageObjectKey = IMPORT_OBJECT_PREFIX + UUID.randomUUID();
        return importJobRepository.save(ImportJob.pending(originalFilename, storageObjectKey));
    }

    // Performs a pure state transition when no additional stage result needs to be persisted that is used when an import stage begins
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transition(Long jobId, ImportStatus expected, ImportStatus next) {
        ImportJob job = getJobWithExpectedStatus(jobId, expected);
        job.transitionTo(next);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeValidation(Long jobId, FileExtension fileExtension) {
        ImportJob job = getJobWithExpectedStatus(jobId, ImportStatus.VALIDATING);
        job.completeValidation(fileExtension);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRawStorage(Long jobId, String storageObjectKey) {
        ImportJob job = getJobWithExpectedStatus(jobId, ImportStatus.STORING);
        job.completeRawStorage(storageObjectKey);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long jobId, ImportStatus expected, ImportStatus failureStatus, String errorCode, String errorMessage) {
        ImportJob job = getJobWithExpectedStatus(jobId, expected);

        job.transitionToFailure(failureStatus, errorCode, errorMessage);
    }

    private ImportJob getJobWithExpectedStatus(Long jobId, ImportStatus expected) {
        ImportJob job = importJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Import job not found: " + jobId));

        if (job.getStatus() != expected) {
            throw new IllegalStateException(
                    "Import job %d expected %s, but was %s"
                            .formatted(jobId, expected, job.getStatus()));
        }
        return job;
    }
}
