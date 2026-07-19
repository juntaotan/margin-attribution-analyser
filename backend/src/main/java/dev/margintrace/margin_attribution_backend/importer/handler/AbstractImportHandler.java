package dev.margintrace.margin_attribution_backend.importer.handler;

import dev.margintrace.margin_attribution_backend.importer.context.ImportContext;

public abstract class AbstractImportHandler implements ImportHandler {

    private ImportHandler nextHandler;

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
