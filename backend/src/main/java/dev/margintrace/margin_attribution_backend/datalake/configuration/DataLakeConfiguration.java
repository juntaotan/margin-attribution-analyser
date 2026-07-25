package dev.margintrace.margin_attribution_backend.datalake.configuration;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataLakeConfiguration {
    /** 
     * Creates and configures a {@link MinioClient} using the data lake connection properties defined in the application 
     * configuration.
     * 
     * @param endpoint  the MinIO server endpoint configured by {@code datalake.endpoint}
     * @param accessKey the access key used to authenticate with MinIO by {@code datalake.access-key}
     * @param secretKey the secret key used to authenticate with MinIO by {@code datalake.secret-key}
     * @return a configured {@link MinioClient} instance
     */
    @Bean
    MinioClient minioClient (
            @Value("${datalake.endpoint}") String endpoint,
            @Value("${datalake.access-key}") String accessKey,
            @Value("${datalake.secret-key}") String secretKey) {
        // Build up a MinioClient object using the endpoint, access key, and secret key from the application properties
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
