package dev.margintrace.margin_attribution_backend.report;

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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

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

    @PostMapping("/preview-values")
    public ReportPlaceholderResolutionService.PlaceholderValues previewValues(
            @RequestBody ReportRequest request) {
        validateRequest(request);
        return resolutionService.resolve(
                request.actualStartDate(),
                request.actualEndDate(),
                request.comparableStartDate(),
                request.comparableEndDate()
        );
    }

    @PostMapping("/instantiate")
    public Object instantiateReport(
            @RequestBody ReportRequest request) throws Exception {
        validateRequest(request);

        // 1. Load cloned master template from Data Lake (MinIO)
        byte[] templateBytes = templateStore.loadOrCreateMasterTemplate();
        ReportTemplateStore.TemplateMetadata meta = templateStore.getMetadata();

        // 2. Resolve placeholder values (AI + SQL)
        ReportPlaceholderResolutionService.PlaceholderValues values = resolutionService.resolve(
                request.actualStartDate(),
                request.actualEndDate(),
                request.comparableStartDate(),
                request.comparableEndDate()
        );

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
                    request.comparableEndDate() != null ? request.comparableEndDate() : ""
            );

            return Map.of(
                    "success", true,
                    "title", generatedFilename,
                    "redirectUrl", "/report-studio" + queryParams,
                    "updatedControls", populated.updatedControls(),
                    "documentVersion", documentStore.currentVersion()
            );
        }

        // Default mode: direct download file
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + generatedFilename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(populated.documentBytes().length)
                .body(populated.documentBytes());
    }

    private void validateRequest(ReportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Report request is required");
        }
        if (request.actualStartDate() == null || request.actualStartDate().isBlank()
                || request.actualEndDate() == null || request.actualEndDate().isBlank()) {
            throw new IllegalArgumentException("Actual period start and end dates are required");
        }
    }

    private String generateFilename(ReportRequest request) {
        String periodTag = request.actualStartDate().replace("-", "") + "_" + request.actualEndDate().replace("-", "");
        return "Management_Commentary_" + periodTag + ".docx";
    }

    public record ReportRequest(
            String actualStartDate,
            String actualEndDate,
            String comparableStartDate,
            String comparableEndDate,
            String mode
    ) {}
}
