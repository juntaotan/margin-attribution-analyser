package dev.margintrace.margin_attribution_backend.importation.service;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs the transformation and write stages of stored imports in the background.
 */
@Service
@RequiredArgsConstructor
public class ImportTaskExecutor {
    private final ImportWorkflow importWorkflow;
    private final SchemaMappingPresetCatalog presetCatalog;

    /**
     * Processes a stored file asynchronously. Stage failures are recorded on the job rather than thrown.
     *
     * @param jobId the job, which must be in the {@code STORED} status
     * @param mappingTableName the confirmed target table
     * @param objectKey the raw file's object key
     */
    @Async
    public void process(Long jobId, String mappingTableName, String objectKey) {
        var definition = presetCatalog.getRequiredByTableName(mappingTableName);
        ImportContext context = new ImportContext();
        context.setMappingTableName(definition.tableName());
        context.setMappingResult(true);
        context.setDataSetDefinition(definition);
        context.setObjectKey(objectKey);
        importWorkflow.processStoredFile(jobId, context);
    }
}
