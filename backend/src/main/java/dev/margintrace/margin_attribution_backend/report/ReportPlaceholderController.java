package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/report-studio")
@RequiredArgsConstructor
public class ReportPlaceholderController {

    private final ReportPlaceholderService placeholderService;
    private final OnlyOfficeController onlyOfficeController;

    @GetMapping("/placeholders")
    public List<ReportPlaceholderService.PlaceholderConfigDto> getConfigurations(
            @RequestParam(defaultValue = "default") String documentId,
            @RequestParam(required = false) List<String> tags) {
        return placeholderService.findConfigurations(documentId, tags);
    }

    @PostMapping("/placeholders/batch")
    public List<ReportPlaceholderService.PlaceholderConfigDto> syncAndGetConfigurations(
            @RequestBody BatchSyncRequest request) {
        String docId = request == null || request.documentId() == null ? "default" : request.documentId();
        List<ReportPlaceholderService.PlaceholderSyncItem> items =
                request == null || request.placeholders() == null ? List.of() : request.placeholders();
        return placeholderService.syncPlaceholders(docId, items);
    }

    @PostMapping("/placeholders/generate")
    public ReportPlaceholderService.GenerateResult generate(
            @RequestBody ReportPlaceholderService.GenerateRequest request) {
        try {
            return placeholderService.generate(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (AiUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    @PostMapping("/query-runs/{runId}/execute")
    public ReportQueryService.QueryResult executeRun(
            @PathVariable UUID runId) {
        try {
            return placeholderService.executeRun(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/placeholders/apply")
    public synchronized Map<String, Object> applyPlaceholder(
            @RequestBody ApplyRequest request) throws Exception {
        if (request == null || request.runId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query runId is required to apply");
        }
        try {
            ReportPlaceholderService.ApplyResult result =
                    placeholderService.applyRun(request.runId(), request.alias());
            onlyOfficeController.invalidateIssuedDocumentKeys();
            return Map.of(
                    "documentVersion", result.documentVersion(),
                    "updatedControls", result.updatedControls(),
                    "runId", result.runId().toString(),
                    "appliedValue", result.appliedValue());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    public record BatchSyncRequest(
            String documentId,
            List<ReportPlaceholderService.PlaceholderSyncItem> placeholders) {
    }

    public record ApplyRequest(
            UUID runId,
            String alias) {
    }
}
