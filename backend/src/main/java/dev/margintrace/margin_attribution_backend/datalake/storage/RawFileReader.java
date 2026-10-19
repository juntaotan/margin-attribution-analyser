package dev.margintrace.margin_attribution_backend.datalake.storage;

import io.minio.errors.MinioException;
import java.io.InputStream;

/**
 * Reads raw files from the data lake.
 */
public interface RawFileReader {
    /**
     * @param objectKey the key of the stored file
     * @return the file content; the caller must close the stream
     * @throws MinioException if the object cannot be read
     */
    InputStream readFile(String objectKey) throws MinioException;
}
