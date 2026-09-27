package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/settings/ai")
@RequiredArgsConstructor
public class AiSettingsController {
    private final AiSettingsService settingsService;

    @GetMapping
    public SettingsResponse settings() {
        return SettingsResponse.from(settingsService.current());
    }

    @PutMapping
    public SettingsResponse update(@RequestBody SettingsRequest request) {
        return SettingsResponse.from(settingsService.update(
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.model()));
    }

    @PostMapping("/test")
    public AiRuntimeManager.ConnectionTest test(@RequestBody SettingsRequest request) {
        return settingsService.test(
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.model());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    public record SettingsRequest(String host, Integer port, String model) {
    }

    public record SettingsResponse(
            String host,
            int port,
            String model,
            String baseUrl,
            Instant updatedAt,
            boolean persisted) {
        static SettingsResponse from(AiConnectionSettings settings) {
            return new SettingsResponse(
                    settings.host(),
                    settings.port(),
                    settings.model(),
                    settings.baseUrl(),
                    settings.updatedAt(),
                    settings.updatedAt() != null);
        }
    }
}
