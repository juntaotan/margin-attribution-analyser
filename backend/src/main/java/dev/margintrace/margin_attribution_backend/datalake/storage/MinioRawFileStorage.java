package dev.margintrace.margin_attribution_backend.datalake.storage;

import dev.margintrace.margin_attribution_backend.datalake.model.StoredObject;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class MinioRawFileStorage implements RawFileStorage {

    private final MinioClient minioClient;
    private final MinioBucketProvisioner bucketProvisioner;

    @Value("${datalake.raw-bucket}")
    private String bucket;

    /** 
     * Stores the provided file in the configured MinIO bucket using the specified object key. The method reads the file's
     * input stream and uploads it to MinIO, returning a {@link StoredObject} containing metadata about the stored file.
     * 
     * @param file the file that user imports to be stored
     * @param objectKey the key under which the file will be stored in the data lake
     * @return StoredObject
     * @throws Exception if an error occurs during the upload process
     */
    @Override
    public StoredObject store(MultipartFile file, String objectKey) throws Exception {
        bucketProvisioner.ensureRawBucketExists();

        try (InputStream input = file.getInputStream()) {
            // Create a upload request to MinIO with the specified bucket, object (key), input content, file size, and content type
            ObjectWriteResponse response = minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .stream(input, file.getSize(), -1L)
                            .contentType(file.getContentType())
                            .build()
            );

            return new StoredObject(bucket, objectKey, response.etag(), file.getSize());
        }
    }
}
