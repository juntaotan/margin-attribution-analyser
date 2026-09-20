package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationAnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationAnalysisResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationPeriodRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.ReconciliationStreamEvent;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportResponse;
import dev.margintrace.margin_attribution_backend.analysis.service.Analyser;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.io.IOException;
import java.io.UncheckedIOException;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final Analyser analyser;
    private final ObjectMapper objectMapper;
    private final RootCauseReportService rootCauseReportService;

    @PostMapping("/root-cause-report")
    public RootCauseReportResponse rootCauseReport(@RequestBody RootCauseReportRequest request) {
        return rootCauseReportService.report(request);
    }

    /** Sends the CSR snapshots first, then threshold paths from those same snapshots. */
    @PostMapping(value = "/reconcile-periods/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> streamReconcilePeriods(
            @RequestBody ReconciliationPeriodRequest request) {
        UUID analysisId = UUID.randomUUID();
        StreamingResponseBody body = output -> {
            try {
                analyser.streamReconciliation(
                        request.actualStartDate(), request.actualEndDate(),
                        request.comparableStartDate(), request.comparableEndDate(),
                        request.leafThreshold(), request.stopThreshold(), analysisId,
                        event -> {
                            try {
                                writeEvent(output, event);
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        });
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            } catch (RuntimeException exception) {
                writeEvent(output, ReconciliationStreamEvent.error(analysisId,
                        exception.getMessage() == null ? "Analysis failed" : exception.getMessage()));
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    private void writeEvent(java.io.OutputStream output, ReconciliationStreamEvent event) throws IOException {
        output.write(objectMapper.writeValueAsBytes(event));
        output.write('\n');
        output.flush();
    }

    /** Compares two supplied CSR graphs and returns complete cost-difference paths. */
    @PostMapping("/reconcile")
    public ResponseEntity<ReconciliationAnalysisResponse> reconcile(
            @RequestBody ReconciliationAnalysisRequest request) {
        return ResponseEntity.ok(analyser.reconcileGraphs(
                request.actualGraph(), request.comparableGraph(),
                request.leafThreshold(), request.stopThreshold()));
    }

    /** Builds and compares the CSR graphs for two selected periods. */
    @PostMapping("/reconcile-periods")
    public ResponseEntity<ReconciliationAnalysisResponse> reconcilePeriods(
            @RequestBody ReconciliationPeriodRequest request) {
        return ResponseEntity.ok(analyser.reconcilePeriods(
                request.actualStartDate(), request.actualEndDate(),
                request.comparableStartDate(), request.comparableEndDate(),
                request.leafThreshold(), request.stopThreshold()));
    }

    /**
     * Runs the date-scoped, multi-target trace and returns JSON-safe adjacency entries.
     * Each entry contains the original upstream Node and its direct downstream Nodes.
     *
     * @param request inclusive dates; targets are selected from sales in that period
     * @return an analysis ID and the traced adjacency entries
     */
    @PostMapping("/trace")
    public ResponseEntity<AnalysisResults> trace(@RequestBody AnalysisRequest request) {
        AnalysisResults response = analyser.analyser(
                request.getStartDate(), request.getEndDate());
        return ResponseEntity.ok(response);
    }

    /**
     * Traces the multi-level Bill of Materials (BOM) hierarchy for the requested target products.
     *
     * @param request optional target product inventory IDs
     * @return an analysis ID and the traced BOM adjacency entries
     */
    @PostMapping("/bom")
    public ResponseEntity<AnalysisResults> traceBom(@RequestBody AnalysisRequest request) {
        AnalysisResults response = analyser.traceBom(
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
