package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.algorithm.workflow.*;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisRunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse;
import dev.margintrace.margin_attribution_backend.algorithm.execution.BackendRegistry;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionPolicy;

/** Direct synchronous adapter for the reusable original-record analysis workflow. */
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisRunController {
    private final AnalysisRunService service;
    public AnalysisRunController(AnalysisRunService service) { this.service = service; }
    @PostMapping("/run")
    public tools.jackson.databind.JsonNode run(@RequestBody AnalysisCommand command) {
        return dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse.json(service.run(command, event -> {}));
    }

    /** Publish real workflow stages once as NDJSON; disconnects stop further submissions. */
    @PostMapping(value = "/run/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody AnalysisCommand command) {
        StreamingResponseBody body = output -> {
            try {
                service.run(command, event -> {
                    try {
                        output.write(AnalysisRunResponse.bytes(event));
                        output.write('\n'); output.flush();
                    } catch (IOException error) { throw new UncheckedIOException(error); }
                });
            } catch (UncheckedIOException error) { throw error.getCause(); }
            catch (RuntimeException error) {
                output.write(AnalysisRunResponse.bytes(Map.of("type", "error", "message",
                        error.getMessage() == null ? "Analysis failed" : error.getMessage())));
                output.write('\n'); output.flush();
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson")).body(body);
    }

    /** Report registered backend capabilities, rather than browser GPU availability. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        var registry = new BackendRegistry();
        return Map.of("cpu", registry.resolve("FGW", ExecutionPolicy.cpu()).report(),
                "cuda", registry.resolve("FGW", new ExecutionPolicy(true, true, null, false)).report(),
                "opencl", registry.resolve("FGW", new ExecutionPolicy(true, false, null, false)).report());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage() == null ? "Invalid analysis input" : error.getMessage()));
    }
}
