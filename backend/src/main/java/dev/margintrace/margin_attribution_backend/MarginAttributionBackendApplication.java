package dev.margintrace.margin_attribution_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Spring Boot entry point of the MarginTrace backend. Asynchronous execution is enabled for background import
 * processing.
 */
@SpringBootApplication
@EnableAsync
public class MarginAttributionBackendApplication {

    /**
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(MarginAttributionBackendApplication.class, args);
    }
}
