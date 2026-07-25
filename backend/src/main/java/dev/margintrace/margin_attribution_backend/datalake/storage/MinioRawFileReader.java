package dev.margintrace.margin_attribution_backend.datalake.storage;

import dev.margintrace.margin_attribution_backend.datalake.model.RawObjectMetadata;
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
     *
     * @param objectKey Key of the object to read from the storage.
     * @return
     */
    @Override
    public InputStream readFile(String objectKey) throws MinioException {

        InputStream input = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build()
        );

        return input;
    }
}
