package dev.margintrace.margin_attribution_backend.importer.context;

import dev.margintrace.margin_attribution_backend.importer.model.ImportStatus;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

@Getter
@Setter
public class ImportContext {
    MultipartFile file;
    ImportStatus status;
}
