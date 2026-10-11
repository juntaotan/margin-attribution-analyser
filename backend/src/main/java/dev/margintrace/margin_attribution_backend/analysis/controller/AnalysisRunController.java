package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.algorithm.workflow.*;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisRunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

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
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage() == null ? "Invalid analysis input" : error.getMessage()));
    }
}
