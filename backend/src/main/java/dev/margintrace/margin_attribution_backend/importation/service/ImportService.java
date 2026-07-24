package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * <h1>Data Import Service</h1>
 *
 * <p>
 *      The end-to-end data import workflow consists of the following stages, including file validation, structural
 *      analysis,schema inference, record transformation, and persistence.
 * </p>
 *
 * <ol>
 *      <li>
 *          Validate the selected file, including its format and size. And use state machine to control whole import job.
 *      </li>
 *      <li>
 *          Process the file using a streaming approach to avoid loading the entire:
 *          <ol>
 *              <li>
 *                  Detect the effective data boundaries, including valid row range and valid column range.
 *              </li>
 *              <li>
 *                  Identify field names and infer field data types from a configurable sample of records.
 *              </li>
 *              <li>
 *                  Transform the imported records into the application's canonical data model.
 *              </li>
 *              <li>
 *                  Return a summary containing the status, import row count, rejected row count and validation errors.
 *              </li>
 *          </ol>
 *      </li>
 * </ol>
 */
@Service("excelImportService")
@RequiredArgsConstructor
public class ImportService {

    // Manage import job state transition
    private final ImportJobStateService stateService;
    // Executes the import workflow
    private final ImportWorkflow importWorkflow;

    public Long importer(MultipartFile file) {
        // Context to collect and transfer information in whole import service chain
        ImportContext context = new ImportContext();
        context.setFile(file);
        // Create a PENDING transaction
        ImportJob job = stateService.createPendingJob(file.getOriginalFilename());
        importWorkflow.execute(job.getId(), context);
        return job.getId();
    }
}
