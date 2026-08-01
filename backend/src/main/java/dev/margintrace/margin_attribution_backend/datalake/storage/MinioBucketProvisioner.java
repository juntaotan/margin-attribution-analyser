package dev.margintrace.margin_attribution_backend.datalake.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Ensures that the configured raw-data bucket exists before it is used. */
@Component
@RequiredArgsConstructor
public class MinioBucketProvisioner {

    private final MinioClient minioClient;

    @Value("${datalake.raw-bucket}")
    private String bucket;

    /**
     * Creates the raw bucket when it does not already exist.
     *
     * <p>The second existence check handles the case where another application instance creates the bucket between
     * this instance's existence check and creation request.</p>
     */
    public synchronized void ensureRawBucketExists() throws Exception {
        if (bucketExists()) {
            return;
        }

        try {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(bucket)
                            .build()
            );
        } catch (Exception creationFailure) {
            try {
                if (bucketExists()) {
                    return;
                }
            } catch (Exception verificationFailure) {
                creationFailure.addSuppressed(verificationFailure);
            }
            throw creationFailure;
        }
    }

    private boolean bucketExists() throws Exception {
        return minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(bucket)
                        .build()
        );
    }
}
