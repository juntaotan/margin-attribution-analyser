package dev.margintrace.margin_attribution_backend.importation.context;

import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import dev.margintrace.margin_attribution_backend.importation.model.ImportStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
public class ImportContext {
    MultipartFile file;
    ImportStatus status;
    FileExtension extension;
}
