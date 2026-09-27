package dev.margintrace.margin_attribution_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MarginAttributionBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(MarginAttributionBackendApplication.class, args);
	}

}
