package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.analysis.service.LocalLlamaClient;

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
    private final LocalLlamaClient llamaClient;
    private final Set<String> issuedDocumentKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> supersededDocumentKeys = ConcurrentHashMap.newKeySet();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Value("${onlyoffice.public-url}")
    private String publicUrl;

    @Value("${onlyoffice.backend-internal-url}")
    private String backendInternalUrl;

    @Value("${onlyoffice.internal-url}")
    private String onlyOfficeInternalUrl;

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
        document.put("permissions", Map.of(
                "edit", true,
                "download", true,
                "print", true
        ));

        Map<String, Object> editorConfig = new LinkedHashMap<>();
        editorConfig.put("mode", "edit");
        editorConfig.put("lang", "en");
        editorConfig.put("callbackUrl",
                baseBackendUrl + "/api/report-studio/onlyoffice/callback?accessToken=" + accessToken);
        editorConfig.put("user", Map.of("id", "margintrace-local-user", "name", "MarginTrace User"));
        editorConfig.put("customization", Map.of("autosave", true, "forcesave", true));

        Map<String, Object> signedConfig = new LinkedHashMap<>();
        signedConfig.put("document", document);
        signedConfig.put("documentType", "word");
        signedConfig.put("editorConfig", editorConfig);
        signedConfig.put("type", "desktop");
        signedConfig.put("token", jwtSigner.sign(signedConfig));

        return Map.of(
                "documentServerUrl", withoutTrailingSlash(publicUrl),
                "config", signedConfig
        );
    }

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

        return Map.of(
                "title", title,
                "documentVersion", documentStore.currentVersion()
        );
    }

    @GetMapping("/content-controls")
    public Map<String, Object> contentControls() throws Exception {
        byte[] content = documentStore.loadOrCreateDefaultDocument();
        List<ReportContentControlScanner.ContentControlDescriptor> controls =
                contentControlScanner.scan(content);
        return Map.of(
                "documentVersion", documentStore.currentVersion(),
                "controls", controls
        );
    }

    @PostMapping("/content-controls/duration")
    public ReportDurationService.DurationAnalysis analyzeDuration(
            @RequestBody DurationAnalysisRequest request) throws Exception {
        try {
            return reportDurationService.analyze(
                    request == null ? null : request.tag(),
                    request == null ? null : request.wordId(),
                    request == null ? null : request.alias());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/content-controls/blueprint")
    public LocalLlamaClient.Blueprint generateBlueprint(
            @RequestBody BlueprintRequest request) {
        String prompt = request == null ? null : request.prompt();
        if (prompt == null || prompt.isBlank() || prompt.length() > 10_000) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Enter a valid prompt before generating a Blueprint");
        }
        LocalLlamaClient.BlueprintResult result = llamaClient.generateBlueprint(prompt);
        if (!result.generated()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, result.message());
        }
        return result.blueprint();
    }

    @PostMapping("/callback")
    public synchronized Map<String, Integer> callback(
            @RequestParam String accessToken,
            @RequestBody Map<String, Object> callback) throws Exception {
        verifyAccessToken(accessToken);
        int status = callback.get("status") instanceof Number number ? number.intValue() : 0;
        String callbackKey = callback.get("key") instanceof String key ? key : "";

        if (supersededDocumentKeys.contains(callbackKey)) {
            return Map.of("error", 0);
        }

        if ((status == 2 || status == 6) && callback.get("url") instanceof String documentUrl) {
            URI uri = OnlyOfficeDownloadUrlResolver.resolve(
                    publicUrl,
                    onlyOfficeInternalUrl,
                    URI.create(documentUrl));
            HttpResponse<byte[]> response = httpClient.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
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
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
        if (fileName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A DOCX file name is required");
        }
        return fileName.length() <= 200 ? fileName : fileName.substring(fileName.length() - 200);
    }

    public record BlueprintRequest(String prompt) {
    }

    public record DurationAnalysisRequest(String tag, String wordId, String alias) {
    }
}
