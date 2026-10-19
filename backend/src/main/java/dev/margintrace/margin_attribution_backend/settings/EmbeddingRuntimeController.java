package dev.margintrace.margin_attribution_backend.settings;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Status and explicit installation of the local embedding runtime and model. */
@RestController
@RequestMapping("/api/v1/settings/embedding")
@RequiredArgsConstructor
public class EmbeddingRuntimeController {
    private final EmbeddingRuntimeService service;

    @GetMapping
    public EmbeddingRuntimeService.Status status() {
        return service.status();
    }

    /** Start installing the {@code cpu} or {@code cuda} variant; poll {@link #status()} for progress. */
    @PostMapping("/install")
    public EmbeddingRuntimeService.Status install(@RequestBody InstallRequest request) {
        return service.install(request == null ? null : request.variant());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", exception.getMessage()));
    }

    public record InstallRequest(String variant) {}
}
