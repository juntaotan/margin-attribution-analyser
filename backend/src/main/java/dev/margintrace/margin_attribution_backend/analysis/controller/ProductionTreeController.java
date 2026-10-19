package dev.margintrace.margin_attribution_backend.analysis.controller;

import dev.margintrace.margin_attribution_backend.analysis.dto.*;
import dev.margintrace.margin_attribution_backend.analysis.service.ProductionTreeService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Synchronous inspection and comparison endpoints for production-ID evidence trees. */
@RestController
@RequestMapping("/api/v1/analysis/production-trees")
public class ProductionTreeController {
    private final ProductionTreeService service;

    public ProductionTreeController(ProductionTreeService service) {
        this.service = service;
    }

    /** Return accepted/incomplete trees and explicit rejected identity statuses. */
    @PostMapping("/inspect")
    public ProductionTreeResponse inspect(@RequestBody ProductionTreeRequest request) {
        return service.inspect(request);
    }

    /** Return eligibility evidence or the existing Java engine's complete pairwise result. */
    @PostMapping("/compare")
    public ProductionComparisonResponse compare(@RequestBody ProductionComparisonRequest request) {
        return service.compare(request);
    }

    /** Malformed selection or mathematical arguments return HTTP 400, not a numerical result. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(Map.of(
                        "message",
                        exception.getMessage() == null
                                ? "Invalid production analysis request"
                                : exception.getMessage()));
    }
}
