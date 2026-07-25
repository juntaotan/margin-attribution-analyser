package dev.margintrace.margin_attribution_backend.datalake.storage;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class MinioRawFileReader implements RawFileReader{

    private final MinioClient minioClient;

    @Value("${datalake.raw-bucket}")
    private String bucket;

    /**
     * Reads a raw file from the configured MinIO bucket.
     *
     * @param objectKey the unique object key used to locate the file in MinIO
     * @return an input stream containing the object's data; the caller is responsible for closing the stream after use
     *
     * @throws MinioException if MinIO fails to process the object retrieval request
     */
    @Override
    public InputStream readFile(String objectKey) throws MinioException {

        InputStream input = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
        );

        return input;
    }
}
