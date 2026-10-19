package dev.margintrace.margin_attribution_backend.report;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints that fill the master template with calculated management-commentary values, either for download or
 * as the Report Studio document.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/report")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReportInstantiateController {

    private final ReportTemplateStore templateStore;
    private final ReportPlaceholderResolutionService resolutionService;
    private final ReportContentControlUpdater contentControlUpdater;
    private final ReportDocumentStore documentStore;
    private final OnlyOfficeController onlyOfficeController;
    private final ReportPlaceholderService placeholderService;

    /**
     * @param request the current and comparison periods
     * @return the values that instantiation would write into the template
     * @throws IllegalArgumentException if the request or the current period dates are missing
     */
    @PostMapping("/preview-values")
    public ReportPlaceholderResolutionService.PlaceholderValues previewValues(@RequestBody ReportRequest request) {
        validateRequest(request);
        return resolutionService.resolve(
                request.actualStartDate(),
                request.actualEndDate(),
                request.comparableStartDate(),
                request.comparableEndDate());
    }

    /**
     * Fills a copy of the master template with the calculated values.
     *
     * <p>In {@code open_in_studio} mode the result replaces the Report Studio document, the default placeholder
     * configurations are seeded with the current period, and open editor sessions are superseded. Otherwise the
     * document is returned as a download.
     *
     * @param request the periods and mode
     * @return for {@code open_in_studio}, the title, a Report Studio redirect URL, the number of updated controls
     *     and the document version; otherwise a DOCX attachment
     * @throws IllegalArgumentException if the request or the current period dates are missing
     * @throws Exception if the template or document cannot be loaded or stored
     */
    @PostMapping("/instantiate")
    public Object instantiateReport(@RequestBody ReportRequest request) throws Exception {
        validateRequest(request);

        // 1. Load cloned master template from Data Lake (MinIO)
        byte[] templateBytes = templateStore.loadOrCreateMasterTemplate();
        ReportTemplateStore.TemplateMetadata meta = templateStore.getMetadata();

        // 2. Resolve placeholder values (AI + SQL)
        ReportPlaceholderResolutionService.PlaceholderValues values = resolutionService.resolve(
                request.actualStartDate(),
                request.actualEndDate(),
                request.comparableStartDate(),
                request.comparableEndDate());

        // 3. Populate Word template
        ReportContentControlUpdater.UpdateResult populated =
                contentControlUpdater.replaceContents(templateBytes, values.replacements());

        String generatedFilename = generateFilename(request);

        // 4. Branch by mode
        if ("open_in_studio".equalsIgnoreCase(request.mode())) {
            // Save as active document in Report Studio
            documentStore.replaceDefaultDocument(populated.documentBytes(), generatedFilename);

            // Auto-configure prompts and duration in PostgreSQL for Report Studio
            String duration = request.actualStartDate() + " to " + request.actualEndDate();
            placeholderService.seedOrUpdateConfigurations("default", duration);

            onlyOfficeController.invalidateIssuedDocumentKeys();

            String queryParams = String.format(
                    "?actualStartDate=%s&actualEndDate=%s&comparableStartDate=%s&comparableEndDate=%s",
                    request.actualStartDate(),
                    request.actualEndDate(),
                    request.comparableStartDate() != null ? request.comparableStartDate() : "",
                    request.comparableEndDate() != null ? request.comparableEndDate() : "");

            return Map.of(
                    "success",
                    true,
                    "title",
                    generatedFilename,
                    "redirectUrl",
                    "/report-studio" + queryParams,
                    "updatedControls",
                    populated.updatedControls(),
                    "documentVersion",
                    documentStore.currentVersion());
        }

        // Default mode: direct download file
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + generatedFilename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(populated.documentBytes().length)
                .body(populated.documentBytes());
    }

    private void validateRequest(ReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Report request is required");
        }
        if (request.actualStartDate() == null
                || request.actualStartDate().isBlank()
                || request.actualEndDate() == null
                || request.actualEndDate().isBlank()) {
            throw new IllegalArgumentException("Actual period start and end dates are required");
        }
    }

    private String generateFilename(ReportRequest request) {
        String periodTag = request.actualStartDate().replace("-", "") + "_"
                + request.actualEndDate().replace("-", "");
        return "Management_Commentary_" + periodTag + ".docx";
    }

    /**
     * Periods to report on, as ISO dates, and the mode: {@code open_in_studio} or anything else for a download.
     */
    public record ReportRequest(
            String actualStartDate,
            String actualEndDate,
            String comparableStartDate,
            String comparableEndDate,
            String mode) {}
}
