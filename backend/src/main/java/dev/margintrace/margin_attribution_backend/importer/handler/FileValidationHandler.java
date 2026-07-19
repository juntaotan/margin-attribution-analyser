package dev.margintrace.margin_attribution_backend.importer.handler;

import dev.margintrace.margin_attribution_backend.importer.context.ImportContext;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileValidationHandler extends AbstractImportHandler{
    @Override
    public void doImport(ImportContext context) {
        MultipartFile file = context.getFile();
    }
}
