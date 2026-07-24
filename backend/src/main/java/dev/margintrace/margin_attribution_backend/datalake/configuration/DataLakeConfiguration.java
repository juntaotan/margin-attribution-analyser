package dev.margintrace.margin_attribution_backend.datalake.configuration;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataLakeConfiguration {
    @Bean
    MinioClient minioClient (
            @Value("${datalake.endpoint}")String endpoint,
            @Value("${datalake.access-key}") String accessKey,
            @Value("${datalake.secret-key}") String secretKey) {
        return MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
    }
}
