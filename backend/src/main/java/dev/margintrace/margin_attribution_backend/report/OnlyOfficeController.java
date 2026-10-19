package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Connects the Report Studio editor to the ONLYOFFICE Document Server and to the report placeholder services.
 *
 * <p>The controller serves one shared report document stored by {@link ReportDocumentStore}. It issues signed editor
 * configurations, serves and accepts the document, receives Document Server save callbacks, and exposes the
 * content-control operations used by Report Studio: scanning controls, detecting reporting periods, generating and
 * executing query blueprints, and writing results into controls.
 *
 * <p>Each editor configuration uses a document key derived from the stored document version. When the document is
 * replaced or updated outside the editor, the keys already issued are marked as superseded, and later callbacks for
 * them are acknowledged without saving, so an open editor cannot overwrite the newer document. Methods that change
 * the document are synchronised on this controller.
 */
@RestController
@RequestMapping("/api/report-studio/onlyoffice")
@RequiredArgsConstructor
public class OnlyOfficeController {

    private static final String ACCESS_PURPOSE = "report-studio-default-document";

    private final OnlyOfficeJwtSigner jwtSigner;
    private final ReportDocumentStore documentStore;
    private final ReportContentControlScanner contentControlScanner;
    private final DocxDocumentValidator docxDocumentValidator;
    private final ReportDurationService reportDurationService;
    private final ReportBlueprintService reportBlueprintService;
    private final ReportQueryService reportQueryService;
    private final ReportContentControlUpdater contentControlUpdater;
    private final ReportPlaceholderService reportPlaceholderService;
    private final Set<String> issuedDocumentKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> supersededDocumentKeys = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @Value("${onlyoffice.public-url}")
    private String publicUrl;

    @Value("${onlyoffice.backend-internal-url}")
    private String backendInternalUrl;

    @Value("${onlyoffice.internal-url}")
    private String onlyOfficeInternalUrl;

    /**
     * Creates a signed ONLYOFFICE editor configuration for the current report document, creating the document from
     * the master template first if it does not exist.
     *
     * <p>The document and callback URLs point at the backend's internal URL and carry an access token, so that the
     * Document Server can reach them. The returned document key is recorded as issued.
     *
     * @return the public Document Server URL as {@code documentServerUrl} and the signed configuration as
     *     {@code config}
     * @throws Exception if the document cannot be loaded from or created in object storage
     */
    @GetMapping("/config")
    public synchronized Map<String, Object> config() throws Exception {
        documentStore.loadOrCreateDefaultDocument();
        String accessToken = jwtSigner.accessToken(ACCESS_PURPOSE);
        String baseBackendUrl = withoutTrailingSlash(backendInternalUrl);
        String documentKey = currentDocumentKey();
        issuedDocumentKeys.add(documentKey);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", "docx");
        document.put("key", documentKey);
        document.put("title", documentStore.currentTitle());
        document.put("url", baseBackendUrl + "/api/report-studio/onlyoffice/document?accessToken=" + accessToken);
        document.put(
                "permissions",
                Map.of(
                        "edit", true,
                        "download", true,
                        "print", true));

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "en");
        editorConfig.put(
                "callbackUrl", baseBackendUrl + "/api/report-studio/onlyoffice/callback?accessToken=" + accessToken);
        editorConfig.put("user", Map.of("id", "margintrace-local-user", "name", "MarginTrace User"));
        editorConfig.put("customization", Map.of("autosave", true, "forcesave", true));

        Map<String, Object> signedConfig = new LinkedHashMap<>();
        signedConfig.put("document", document);
        signedConfig.put("documentType", "word");
        signedConfig.put("editorConfig", editorConfig);
        signedConfig.put("type", "desktop");
        signedConfig.put("token", jwtSigner.sign(signedConfig));

        return Map.of("documentServerUrl", withoutTrailingSlash(publicUrl), "config", signedConfig);
    }

    /**
     * Serves the current report document to the Document Server.
     *
     * @param accessToken the token included in the URL of the editor configuration
     * @return the DOCX content, inline, with the stored document title as its file name
     * @throws IllegalArgumentException if the access token is not valid
     * @throws Exception if the document cannot be loaded from object storage
     */
    @GetMapping("/document")
    public ResponseEntity<byte[]> document(@RequestParam String accessToken) throws Exception {
        verifyAccessToken(accessToken);
        byte[] content = documentStore.loadOrCreateDefaultDocument();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline()
                                .filename(documentStore.currentTitle(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    /**
     * Replaces the report document with an uploaded DOCX file. Editor sessions opened for the previous document are
     * superseded, so their saves are ignored.
     *
     * @param file the uploaded Word document; its file name is reduced to a safe base name of at most 200
     *     characters
     * @return the stored {@code title} and the new {@code documentVersion}
     * @throws ResponseStatusException with status 400 if the file name is missing or the file is not a valid DOCX
     *     document
     * @throws Exception if the document cannot be stored
     */
    @PostMapping(value = "/document", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public synchronized Map<String, Object> uploadDocument(
            @org.springframework.web.bind.annotation.RequestPart("file") MultipartFile file) throws Exception {
        String title = sanitizeFileName(file.getOriginalFilename());
        byte[] content = file.getBytes();

        try {
            docxDocumentValidator.validate(title, content);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }

        supersededDocumentKeys.addAll(issuedDocumentKeys);
        issuedDocumentKeys.clear();
        documentStore.replaceDefaultDocument(content, title);

        return Map.of("title", title, "documentVersion", documentStore.currentVersion());
    }

    /**
     * Lists the Word content controls found in the saved report document.
     *
     * @return the {@code documentVersion} that was scanned and the {@code controls} found in it
     * @throws Exception if the document cannot be loaded or parsed
     */
    @GetMapping("/content-controls")
    public Map<String, Object> contentControls() throws Exception {
        byte[] content = documentStore.loadOrCreateDefaultDocument();
        List<ReportContentControlScanner.ContentControlDescriptor> controls = contentControlScanner.scan(content);
        return Map.of("documentVersion", documentStore.currentVersion(), "controls", controls);
    }

    /**
     * Detects the reporting period stated in the text around a content control, using the AI period assistant.
     *
     * @param request the control's tag, Word ID or alias; the first non-blank value selects the control
     * @return the detected period and the sentences it was taken from; AI failures are reported in the result's
     *     message rather than as an error
     * @throws ResponseStatusException with status 400 if no valid selector is given or no matching control exists
     * @throws Exception if the document cannot be loaded or parsed
     */
    @PostMapping("/content-controls/duration")
    public ReportDurationService.DurationAnalysis analyzeDuration(@RequestBody DurationAnalysisRequest request)
            throws Exception {
        try {
            return reportDurationService.analyze(
                    request == null ? null : request.tag(),
                    request == null ? null : request.wordId(),
                    request == null ? null : request.alias());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Generates a query blueprint for a placeholder prompt without saving it or running the query.
     *
     * @param request the natural-language prompt
     * @return the validated blueprint
     * @throws ResponseStatusException with status 400 for an invalid prompt, or 503 if the AI service is
     *     unavailable or returns an invalid blueprint
     */
    @PostMapping("/content-controls/blueprint")
    public ReportBlueprint generateBlueprint(@RequestBody BlueprintRequest request) {
        try {
            return reportBlueprintService.generate(request == null ? null : request.prompt());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (AiUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), exception);
        }
    }

    /**
     * Runs a blueprint's read-only query without saving a query run.
     *
     * @param blueprint the blueprint to execute
     * @return the generated SQL and at most 100 result rows
     * @throws ResponseStatusException with status 400 if the blueprint fails validation or the query fails
     */
    @PostMapping("/content-controls/blueprint/execute")
    public ReportQueryService.QueryResult executeBlueprint(@RequestBody ReportBlueprint blueprint) {
        try {
            return reportQueryService.execute(blueprint);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Writes a value into every content control with the given alias in the saved report document.
     *
     * <p>When the request names a saved query run, the run's formatted result is written and the run is marked as
     * applied; otherwise the request's literal value is written. Open editor sessions are superseded in both cases.
     *
     * @param request a query run ID and alias, or an alias and a literal value
     * @return the new {@code documentVersion}, the number of {@code updatedControls}, and the {@code runId} when a
     *     run was applied
     * @throws ResponseStatusException with status 400 if the alias, value or run is invalid or no control matches
     * @throws Exception if the document cannot be loaded or stored
     */
    @PostMapping("/content-controls/apply")
    public synchronized Map<String, Object> applyContentControl(@RequestBody ApplyContentControlRequest request)
            throws Exception {
        try {
            if (request != null && request.runId() != null) {
                ReportPlaceholderService.ApplyResult result =
                        reportPlaceholderService.applyRun(request.runId(), request.alias());
                invalidateIssuedDocumentKeys();
                return Map.of(
                        "documentVersion", result.documentVersion(),
                        "updatedControls", result.updatedControls(),
                        "runId", result.runId().toString());
            }

            ReportContentControlUpdater.UpdateResult update = contentControlUpdater.replaceContentByAlias(
                    documentStore.loadOrCreateDefaultDocument(),
                    request == null ? null : request.alias(),
                    request == null ? null : request.value());
            invalidateIssuedDocumentKeys();
            documentStore.saveDefaultDocument(update.documentBytes());
            return Map.of(
                    "documentVersion", documentStore.currentVersion(),
                    "updatedControls", update.updatedControls());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    /**
     * Marks every editor configuration issued so far as superseded. Call this after the document is changed outside
     * the editor, so that later saves from those editor sessions are ignored instead of overwriting the change.
     */
    public synchronized void invalidateIssuedDocumentKeys() {
        supersededDocumentKeys.addAll(issuedDocumentKeys);
        issuedDocumentKeys.clear();
    }

    /**
     * Receives a Document Server save callback. For status 2 (ready for saving) and 6 (force save), the edited
     * document is downloaded through the Document Server's internal URL and stored. Callbacks for superseded
     * document keys are acknowledged without saving.
     *
     * @param accessToken the token included in the callback URL
     * @param callback the callback payload sent by the Document Server
     * @return {@code error: 0}, which acknowledges the callback
     * @throws IllegalArgumentException if the access token is not valid or the download URL has an unexpected
     *     origin
     * @throws IllegalStateException if the Document Server returns a non-success status for the download
     * @throws Exception if the download or the storage write fails
     */
    @PostMapping("/callback")
    public synchronized Map<String, Integer> callback(
            @RequestParam String accessToken, @RequestBody Map<String, Object> callback) throws Exception {
        verifyAccessToken(accessToken);
        int status = callback.get("status") instanceof Number number ? number.intValue() : 0;
        String callbackKey = callback.get("key") instanceof String key ? key : "";

        if (supersededDocumentKeys.contains(callbackKey)) {
            return Map.of("error", 0);
        }

        if ((status == 2 || status == 6) && callback.get("url") instanceof String documentUrl) {
            URI uri = OnlyOfficeDownloadUrlResolver.resolve(publicUrl, onlyOfficeInternalUrl, URI.create(documentUrl));
            HttpResponse<byte[]> response =
                    httpClient.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ONLYOFFICE returned document HTTP " + response.statusCode());
            }
            documentStore.saveDefaultDocument(response.body());
        }

        return Map.of("error", 0);
    }

    private void verifyAccessToken(String accessToken) {
        if (!Objects.equals(jwtSigner.accessToken(ACCESS_PURPOSE), accessToken)) {
            throw new IllegalArgumentException("Invalid report document access token");
        }
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String currentDocumentKey() {
        return "margintrace-report-" + documentStore.currentVersion();
    }

    private String sanitizeFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A DOCX file name is required");
        }

        String normalized = originalFileName.replace('\\', '/');
        String fileName = normalized
                .substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A DOCX file name is required");
        }
        return fileName.length() <= 200 ? fileName : fileName.substring(fileName.length() - 200);
    }

    /**
     * Prompt for which to generate a query blueprint.
     */
    public record BlueprintRequest(String prompt) {}

    /**
     * Value to write into content controls: either a saved query run to apply, or a literal value.
     */
    public record ApplyContentControlRequest(java.util.UUID runId, String alias, String value) {
        public ApplyContentControlRequest(String alias, String value) {
            this(null, alias, value);
        }
    }

    /**
     * Selects a content control by tag, Word ID or alias; the first non-blank value is used.
     */
    public record DurationAnalysisRequest(String tag, String wordId, String alias) {}
}
