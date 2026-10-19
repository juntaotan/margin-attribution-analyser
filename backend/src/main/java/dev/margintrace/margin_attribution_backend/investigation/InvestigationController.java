package dev.margintrace.margin_attribution_backend.investigation;

import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Generates an anomaly investigation report for one material and two periods. */
@RestController
@RequestMapping("/api/v1/investigation")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class InvestigationController {
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    /** Takes the same body as {@code POST /api/v1/analysis/root-cause-report}. */
    @PostMapping
    public InvestigationReport investigate(@RequestBody RootCauseReportRequest request) {
        return service.investigate(request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }
}
