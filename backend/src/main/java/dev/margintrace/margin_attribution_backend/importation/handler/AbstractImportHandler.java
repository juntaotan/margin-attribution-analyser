package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;

/**
 * Base class for import stages that can be chained, so that a stage can hand the context to the next one.
 */
public abstract class AbstractImportHandler implements ImportHandler {

    private ImportHandler nextHandler;

    /**
     * @param nextHandler the stage to run after this one
     * @return {@code nextHandler}, so that calls can be chained
     */
    public ImportHandler setNext(ImportHandler nextHandler) {
        this.nextHandler = nextHandler;
        return nextHandler;
    }

    protected void handleNext(ImportContext context) {
        if (nextHandler != null) {
            nextHandler.doImport(context);
        }
    }
}
