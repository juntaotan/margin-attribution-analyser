package dev.margintrace.margin_attribution_backend.settings;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for viewing, testing and saving the llama.cpp connection used by the AI services.
 */
@RestController
@RequestMapping("/api/v1/settings/ai")
@RequiredArgsConstructor
public class AiSettingsController {
    private final AiSettingsService settingsService;

    /**
     * @return the current connection and whether it was saved by a user
     */
    @GetMapping
    public SettingsResponse settings() {
        return SettingsResponse.from(settingsService.current());
    }

    /**
     * Saves the connection and switches the AI services to it.
     *
     * @param request the host, port and model name
     * @return the saved connection
     */
    @PutMapping
    public SettingsResponse update(@RequestBody SettingsRequest request) {
        return SettingsResponse.from(settingsService.update(
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.model()));
    }

    /**
     * Tests a connection without saving it.
     *
     * @param request the host, port and model name
     * @return the health-check outcome
     */
    @PostMapping("/test")
    public AiRuntimeManager.ConnectionTest test(@RequestBody SettingsRequest request) {
        return settingsService.test(
                request == null ? null : request.host(),
                request == null ? null : request.port(),
                request == null ? null : request.model());
    }

    /**
     * Reports invalid connection settings as HTTP 400 with a {@code message}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidSettings(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    /**
     * Connection values entered by the user.
     */
    public record SettingsRequest(String host, Integer port, String model) {}

    /**
     * The current connection. {@code persisted} is false when the connection comes from application properties.
     */
    public record SettingsResponse(
            String host, int port, String model, String baseUrl, Instant updatedAt, boolean persisted) {
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
