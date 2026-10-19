package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;

/**
 * One stage of the spreadsheet import, applied to a shared {@link ImportContext}.
 */
public interface ImportHandler {

    /**
     * Runs this stage, recording its results in the context.
     *
     * @param context the import state
     * @throws RuntimeException if the stage fails; the caller records the failure on the import job
     */
    void doImport(ImportContext context);
}
