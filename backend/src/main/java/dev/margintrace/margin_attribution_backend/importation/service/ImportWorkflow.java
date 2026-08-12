package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.datalake.model.StoredObject;
import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileStorage;
import dev.margintrace.margin_attribution_backend.datalake.key.RawObjectKeyFactory;
import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.handler.ColumnTypeInferHandler;
import dev.margintrace.margin_attribution_backend.importation.handler.DatabaseWriterHandler;
import dev.margintrace.margin_attribution_backend.importation.handler.FileValidationHandler;
import dev.margintrace.margin_attribution_backend.importation.handler.TableBoundaryDetectHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

import static dev.margintrace.margin_attribution_backend.importation.model.ImportStatus.*;

/**
 * Import Workflow
 *
 * <p> Manages the end-to-end import process and determines when an import job should transition to its next state as
 * each business stage begins, succeeds, or fails. </p>
 * 
 * <p> This class coordinates business handlers with {@link ImportJobStateService}. Transition rules belong to the import
 * domain model, while {@code ImportJobStateService} provides the transactional persistence boundary. Individual handlers
 * perform their assigned business operation. </p>
 * 
 * <p> On a business-stage failure, the exception is attached to the {@link ImportContext} for the current execution,
 * while a failure status and a safe error description are recorded against the persistent import job. Once a failure
 * state is reached, subsequent workflow stages are not executed. </p>
 * 
 * <p> The intended import lifecycle is: </p>
 * <ol>
 *     <li> {@code PENDING -> VALIDATING} when file validation begins. </li>
 *     <li> {@code VALIDATING -> VALIDATED} when validation succeeds, or {@code VALIDATING -> VALIDATING_FAILED} when
 *     validation throws an exception. </li>
 *     <li> {@code VALIDATED -> STORING} when raw file storage begins.</li>
 *     <li> {@code STORING -> STORED} when raw file storage succeeds, or {@code STORING -> STORING_FAILED} when raw file
 *     storage fails. </li>
 *     <li> {@code STORED -> TRANSFORMING} when spreadsheet analysis begins.</li>
 *     <li> {@code TRANSFORMING -> TRANSFORMED} when spreadsheet analysis succeeds, or
 *     {@code TRANSFORMING -> TRANSFORMING_FAILED} when it fails.</li>
 *     <li> {@code TRANSFORMED -> WRITING_TO_DATALAKE} when database writing begins.</li>
 *     <li> {@code WRITING_TO_DATALAKE -> WRITE_SUCCESS} when database writing succeeds, or
 *     {@code WRITING_TO_DATALAKE -> WRITE_FAILED} when it fails.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ImportWorkflow {

    private final FileValidationHandler fileValidationHandler;
    private final TableBoundaryDetectHandler tableBoundaryDetectHandler;
    private final ColumnTypeInferHandler columnTypeInferHandler;
    private final DatabaseWriterHandler databaseWriterHandler;
    private final ImportJobStateService stateService;
    private final RawFileStorage rawFileStorage;
    private final RawObjectKeyFactory objectKeyFactory;

    /** 
     * Executes the import workflow for the specified job and context.
     * 
     * <p> The workflow executes the following stages in order: </p>
     * <ol>
     *    <li> File validation: uses the doImport method in {@link FileValidationHandler} </li>
     *    <li> Raw file storage: uses the store method in {@link RawFileStorage} </li>
     *    <li> Spreadsheet analysis: detects table boundaries and infers column types </li>
     *    <li> Database writing: uses the doImport method in {@link DatabaseWriterHandler} </li>
     * </ol>
     * 
     * @param jobId the ID of the import job getting from the {@link ImportService#importer(MultipartFile)} method
     * @param context the import context getting from the {@link ImportService#importer(MultipartFile)} method
     */
    public void execute(Long jobId, ImportContext context) {
        stateService.transition(jobId, PENDING, VALIDATING);

        try {
            fileValidationHandler.doImport(context);
        } catch (Exception e) {
            context.setError(e);
            stateService.fail(jobId, VALIDATING, VALIDATING_FAILED, "FILE_VALIDATION_FAILED", e.getMessage());
            return;
        }

        stateService.completeValidation(jobId, context.getExtension());
        // Transition to STORING state before raw file storage
        stateService.transition(jobId, VALIDATED, STORING);

        try {
            // Generate a unique object key for the raw file storage based on the job ID and file extension
            String objectKey = objectKeyFactory.create(jobId, context.getExtension());
            
            // Call store method in the datalake module to create a stored object in the data lake
            StoredObject storedObject = rawFileStorage.store(context.getFile(), objectKey);
            stateService.completeRawStorage(jobId, storedObject.objectKey());

            context.setObjectKey(storedObject.objectKey());
        } catch (Exception e) {
            context.setError(e);
            stateService.fail(jobId, STORING, STORING_FAILED, "RAW_FILE_STORAGE_FAILED", e.getMessage());
            return;
        }

        stateService.transition(jobId, STORED, TRANSFORMING);

        try {
            // Detect the table boundaries (left, right, top and bottom)
            tableBoundaryDetectHandler.doImport(context);

            // Infer the data type of every column inside the detected table.
            columnTypeInferHandler.doImport(context);

        } catch (Exception e) {
            context.setError(e);
            stateService.fail(
                    jobId,
                    TRANSFORMING,
                    TRANSFORMING_FAILED,
                    "SPREADSHEET_TRANSFORMATION_FAILED",
                    e.getMessage()
            );
            return;
        }

        stateService.transition(jobId, TRANSFORMING, TRANSFORMED);
        stateService.transition(jobId, TRANSFORMED, WRITING_TO_DATALAKE);

        try {
            databaseWriterHandler.doImport(context);
        } catch (Exception e) {
            context.setError(e);
            stateService.fail(
                    jobId,
                    WRITING_TO_DATALAKE,
                    WRITE_FAILED,
                    "DATABASE_WRITE_FAILED",
                    e.getMessage()
            );
            return;
        }

        stateService.transition(jobId, WRITING_TO_DATALAKE, WRITE_SUCCESS);
    }
}
