package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.handler.FileValidationHandler;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import static dev.margintrace.margin_attribution_backend.importation.model.ImportStatus.*;

/**
 * <h1>ImportWorkflow</h1>
 *
 * <p>
 *     Manages the end-to-end import process and determines when an import job should transition to its next state as
 *     each business stage begins, succeeds, or fails.
 * </p>
 * <p>
 *     This class coordinates business handlers with {@link ImportJobStateService}. Transition rules belong to the import
 *     domain model, while {@code ImportJobStateService} provides the transactional persistence boundary. Individual
 *     handlers perform their assigned business operation.
 * </p>
 * <p>
 *     On a business-stage failure, the exception is attached to the {@link ImportContext} for the current execution,
 *     while a failure status and a safe error description are recorded against the persistent import job. Once a failure
 *     state is reached, subsequent workflow stages are not executed.
 * </p>
 * <p> The intended import lifecycle is: </p>
 * <ol>
 *     <li> {@code PENDING -> VALIDATING} when file validation begins. </li>
 *     <li>
 *         {@code VALIDATING -> VALIDATED} when validation succeeds, or
 *         {@code VALIDATING -> VALIDATING_FAILED} when validation throws an exception.
 *     </li>
 *     <li>{@code VALIDATED -> TRANSFORMING} when record transformation begins.</li>
 *     <li>
 *         {@code TRANSFORMING -> TRANSFORMED} when transformation succeeds, or
 *         {@code TRANSFORMING -> TRANSFORMING_FAILED} when transformation fails.
 *     </li>
 *     <li>{@code TRANSFORMED -> WRITING_TO_DATALAKE} when persistence begins.</li>
 *     <li>
 *         {@code WRITING_TO_DATALAKE -> WRITE_SUCCESS} when persistence succeeds, or
 *         {@code WRITING_TO_DATALAKE -> WRITE_FAILED} when persistence fails.
 *     </li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class ImportWorkflow {

    private final FileValidationHandler fileValidationHandler;
    private final ImportJobStateService stateService;

    public void execute(Long jobId, ImportContext context) {
        stateService.transition(jobId, PENDING, VALIDATING);

        try {
            fileValidationHandler.doImport(context);
        } catch (Exception e) {
            context.setError(e);
            stateService.fail(jobId, VALIDATING, VALIDATING_FAILED, "FILE_VALIDATION_FAILED", e.getMessage());
            return;
        }

        stateService.transition(jobId,VALIDATING,VALIDATED);
    }
}
