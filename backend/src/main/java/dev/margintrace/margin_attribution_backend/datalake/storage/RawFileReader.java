package dev.margintrace.margin_attribution_backend.datalake.storage;

import io.minio.errors.MinioException;

import java.io.InputStream;

public interface RawFileReader {
    InputStream readFile (String objectKey) throws MinioException;
}
