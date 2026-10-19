package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * REST endpoints for Report Studio placeholder configurations and query runs.
 */
@RestController
@RequestMapping("/api/report-studio")
@RequiredArgsConstructor
public class ReportPlaceholderController {

    private final ReportPlaceholderService placeholderService;
    private final OnlyOfficeController onlyOfficeController;

    /**
     * @param documentId the document; {@code default} if omitted
     * @param tags the tags to return; all if omitted
     * @return the matching configurations with their latest runs
     */
    @GetMapping("/placeholders")
    public List<ReportPlaceholderService.PlaceholderConfigDto> getConfigurations(
            @RequestParam(defaultValue = "default") String documentId,
            @RequestParam(required = false) List<String> tags) {
        return placeholderService.findConfigurations(documentId, tags);
    }

    /**
     * Registers the controls currently in the document and returns every configuration of the document.
     *
     * @param request the document ID and the controls' tags and aliases
     * @return all configurations of the document
     */
    @PostMapping("/placeholders/batch")
    public List<ReportPlaceholderService.PlaceholderConfigDto> syncAndGetConfigurations(
            @RequestBody BatchSyncRequest request) {
        String docId = request == null || request.documentId() == null ? "default" : request.documentId();
        List<ReportPlaceholderService.PlaceholderSyncItem> items =
                request == null || request.placeholders() == null ? List.of() : request.placeholders();
        return placeholderService.syncPlaceholders(docId, items);
    }

    /**
     * Generates, saves and runs a blueprint for a placeholder prompt.
     *
     * @param request the document, tag, alias and prompt
     * @return the saved configuration and run, with the query result or error
     * @throws ResponseStatusException with status 400 for invalid input, or 503 if the AI service is unavailable
     */
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

    /**
     * @param runId the saved run to execute again
     * @return the new query result
     * @throws ResponseStatusException with status 400 if the run is missing or the query fails
     */
    @PostMapping("/query-runs/{runId}/execute")
    public ReportQueryService.QueryResult executeRun(@PathVariable UUID runId) {
        try {
            return placeholderService.executeRun(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Writes a run's result into the report document and supersedes open editor sessions.
     *
     * @param request the run and, optionally, the alias to write to
     * @return the new {@code documentVersion}, {@code updatedControls}, {@code runId} and {@code appliedValue}
     * @throws ResponseStatusException with status 400 if the run cannot be applied
     * @throws Exception if the document cannot be loaded or stored
     */
    @PostMapping("/placeholders/apply")
    public synchronized Map<String, Object> applyPlaceholder(@RequestBody ApplyRequest request) throws Exception {
        if (request == null || request.runId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A query runId is required to apply");
        }
        try {
            ReportPlaceholderService.ApplyResult result = placeholderService.applyRun(request.runId(), request.alias());
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

    /**
     * Controls found in a document, to register.
     */
    public record BatchSyncRequest(
            String documentId, List<ReportPlaceholderService.PlaceholderSyncItem> placeholders) {}

    /**
     * A query run to write into the document, with an optional alias.
     */
    public record ApplyRequest(UUID runId, String alias) {}
}
