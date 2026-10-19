package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionPolicy;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionReport;
import dev.margintrace.margin_attribution_backend.algorithm.workflow.*;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisRunService;
import dev.margintrace.margin_attribution_backend.infrastructure.acceleration.NativeAcceleration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Direct synchronous adapter for the reusable original-record analysis workflow. */
@RestController
@RequestMapping("/api/v1/analysis")
public class AnalysisRunController {
    private final AnalysisRunService service;

    public AnalysisRunController(AnalysisRunService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public tools.jackson.databind.JsonNode run(@RequestBody AnalysisCommand command) {
        return dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRunResponse.json(
                service.run(command, event -> {}));
    }

    /** Publish real workflow stages once as NDJSON; disconnects stop further submissions. */
    @PostMapping(value = "/run/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody AnalysisCommand command) {
        StreamingResponseBody body = output -> {
            try {
                service.run(command, event -> {
                    try {
                        AnalysisRunResponse.writeEvent(output, event);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                });
            } catch (UncheckedIOException error) {
                throw error.getCause();
            } catch (RuntimeException error) {
                AnalysisRunResponse.writeEvent(
                        output,
                        Map.of(
                                "type",
                                "error",
                                "message",
                                error.getMessage() == null ? "Analysis failed" : error.getMessage()));
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .body(body);
    }

    /** Report registered backend capabilities, rather than browser GPU availability. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        var cuda = NativeAcceleration.inventory("CUDA");
        var opencl = NativeAcceleration.inventory("OPENCL");
        return Map.of(
                "cpu",
                capability("JAVA_CPU", null, "FGW", true),
                "cuda",
                capability("CUDA", cuda, "FGW", true),
                "opencl",
                capability("OPENCL", opencl, "FGW", false),
                "stages",
                Map.of(
                        "cuda",
                        Map.of(
                                "frontier",
                                capability("CUDA", cuda, "FRONTIER", true),
                                "fgw",
                                capability("CUDA", cuda, "FGW", true)),
                        "opencl",
                        Map.of(
                                "frontier",
                                capability("OPENCL", opencl, "FRONTIER", false),
                                "fgw",
                                capability("OPENCL", opencl, "FGW", false))),
                "devices",
                Map.of("cuda", cuda.devices(), "opencl", opencl.devices()));
    }

    private static ExecutionReport capability(
            String family, NativeAcceleration.Inventory inventory, String stage, boolean nvidia) {
        boolean available = inventory == null
                || inventory.devices().stream().anyMatch(d -> stage.equals("FGW") ? d.fgw() : d.frontier());
        String reason = available
                ? null
                : inventory.unavailableReason() == null ? family + "_STAGE_UNAVAILABLE" : inventory.unavailableReason();
        return new ExecutionReport(
                stage,
                family.equals("JAVA_CPU") ? ExecutionPolicy.cpu() : new ExecutionPolicy(true, nvidia, null, false),
                family,
                available ? family : null,
                null,
                reason,
                stage.equals("FRONTIER") ? "SIGNED_LONG_MICRO_UNITS" : "FP64/EXACT_SIMPLEX",
                available ? "AVAILABLE" : "UNAVAILABLE");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest()
                .body(Map.of("message", error.getMessage() == null ? "Invalid analysis input" : error.getMessage()));
    }
}
