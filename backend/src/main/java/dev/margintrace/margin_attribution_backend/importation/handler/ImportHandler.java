package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;

public interface ImportHandler {

    void doImport(ImportContext context);

}
