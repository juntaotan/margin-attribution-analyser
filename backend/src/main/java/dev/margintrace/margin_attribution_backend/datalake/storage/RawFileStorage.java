package dev.margintrace.margin_attribution_backend.datalake.storage;

import dev.margintrace.margin_attribution_backend.datalake.model.StoredObject;
import org.springframework.web.multipart.MultipartFile;

/**
 * RawFileStorage
 * 
 * <p> Provides an interface for storing raw files in a data lake. Implementations of this interface are responsible for
 * handling the storage of files, including generating unique object keys and managing the underlying storage mechanism. </p>
 */
public interface RawFileStorage {
    StoredObject store(MultipartFile file,String objectKey) throws Exception;
}
