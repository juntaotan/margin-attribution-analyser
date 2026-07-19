package dev.margintrace.margin_attribution_backend.importer.handler;

import dev.margintrace.margin_attribution_backend.importer.context.ImportContext;

public interface ImportHandler {

    void doImport(ImportContext context);

}
