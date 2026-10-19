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
    /**
     * @param file the uploaded file
     * @param objectKey the key to store it under
     * @return where the file was stored
     * @throws Exception if the file cannot be read or written
     */
    StoredObject store(MultipartFile file, String objectKey) throws Exception;
}
