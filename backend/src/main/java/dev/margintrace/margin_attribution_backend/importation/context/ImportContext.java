package dev.margintrace.margin_attribution_backend.importation.context;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.FileExtension;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mutable state passed along the import handler chain: the upload and its confirmed target table, and the results
 * of each stage (file type, raw object key, detected table, column types, raw table, column mappings and the number
 * of warehouse rows written). {@code error} holds the exception that stopped the chain, if any.
 */
@Getter
@Setter
public class ImportContext {
    MultipartFile file;
    String mappingTableName;
    boolean mappingResult;
    Exception error;
    FileExtension extension;
    String objectKey;
    TableStructure tableStructure;
    Map<String, DataType> columnTypes;
    String rawTableName;
    Map<String, String> rawColumnNames;
    DataSetDefinition dataSetDefinition;
    Map<String, TargetFieldDefinition> columnMappings;
    int warehouseImportedRows;
}
