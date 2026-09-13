package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final Analyser analyser;

    @PostMapping("/margin-topology")
    public ResponseEntity<AnalysisGraphResponse> analyzeMarginTopology(@RequestBody AnalysisRequest request) {
        AnalysisGraphResponse response = analysisService.analyze(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Runs the date-scoped, multi-target trace and returns JSON-safe adjacency entries.
     * Each entry contains the original upstream Node and its direct downstream Nodes.
     *
     * @param request inclusive dates and optional target inventory IDs
     * @return an analysis ID and the traced adjacency entries
     */
    @PostMapping("/trace")
    public ResponseEntity<AnalysisResults> trace(@RequestBody AnalysisRequest request) {
        AnalysisResults response = analyser.analyser(
                request.getTargets(), request.getStartDate(), request.getEndDate());
        return ResponseEntity.ok(response);
    }

    /**
     * Turns an invalid date range or absent target into a readable HTTP 400 response.
     * This keeps validation failures separate from server-side analysis failures.
     *
     * @param exception the validation failure raised by the workflow
     * @return a JSON object containing the failure message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleInvalidAnalysis(IllegalArgumentException exception) {
        String message = exception.getMessage() == null
                ? "Invalid analysis request" : exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of("message", message));
    }
}
