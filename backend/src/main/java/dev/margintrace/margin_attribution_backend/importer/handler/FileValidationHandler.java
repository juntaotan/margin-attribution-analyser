package dev.margintrace.margin_attribution_backend.importer.handler;

import dev.margintrace.margin_attribution_backend.importer.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importer.model.FileExtension;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class FileValidationHandler extends AbstractImportHandler{
    @Override
    public void doImport(ImportContext context) {
        MultipartFile file = context.getFile();
        // File level validation
        FileExtension extension = validateExcelExtension(file);
        context.setExtension(extension);
    }

    private FileExtension validateExcelExtension(MultipartFile file){
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new IllegalArgumentException("The uploaded file has no valid filename.");
        }
        return FileExtension
                .from(originalFilename.substring(originalFilename.lastIndexOf('.')+1))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported file extension: " + originalFilename
                ));
    }
}
