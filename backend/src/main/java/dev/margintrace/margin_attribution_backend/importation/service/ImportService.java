package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Coordinates the end-to-end file import process.
 *
 * <p> The import process includes file validation, raw file storage, structural analysis, schema inference, record 
 * transformation, persistence, and import status tracking.</p>
 * 
 * <p> Import job state transitions are managed by {@link ImportJobStateService}, while the individual processing stages
 * are executed by {@link ImportWorkflow}.</p>
 */
@Service("excelImportService")
@RequiredArgsConstructor
public class ImportService {

    // Manage import job state transition
    private final ImportJobStateService stateService;
    // Executes the import workflow
    private final ImportWorkflow importWorkflow;
    private final dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog presetCatalog;

    /**
     * Executes the import workflow for the provided file, creating a new import job and returning its ID to allow
     * ImportWorkflow to handle the import job.
     * 
     * @param file the file to import
     * @return the ID of the created import job
     */
    public Long importer(MultipartFile file, String mappingTableName, boolean mappingResult) {
        if (!mappingResult) {
            throw new IllegalArgumentException("Mapping must be confirmed before importing");
        }
        var definition = presetCatalog.getRequiredByTableName(mappingTableName);

        // Context to collect and transfer information in whole import service chain
        ImportContext context = new ImportContext();
        context.setFile(file);
        context.setMappingTableName(definition.tableName());
        context.setMappingResult(mappingResult);
        context.setDataSetDefinition(definition);
        
        // Create a PENDING transaction and write into PostgreSQL
        ImportJob job = stateService.createPendingJob(
                file.getOriginalFilename(), definition.tableName(), mappingResult);
        importWorkflow.execute(job.getId(), context);

        return job.getId();
    }
}
