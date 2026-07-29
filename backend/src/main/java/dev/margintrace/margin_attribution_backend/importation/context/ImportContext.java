package dev.margintrace.margin_attribution_backend.importation.context;

import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Getter
@Setter
public class ImportContext {
    MultipartFile file;
    Exception error;
    FileExtension extension;
    String objectKey;
    TableStructure tableStructure;
    Map<String, DataType> columnTypes;
}
