package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final AnalysisService analysisService;

    @PostMapping("/margin-topology")
    public ResponseEntity<AnalysisGraphResponse> analyzeMarginTopology(@RequestBody AnalysisRequest request) {
        AnalysisGraphResponse response = analysisService.analyze(request);
        return ResponseEntity.ok(response);
    }
}
