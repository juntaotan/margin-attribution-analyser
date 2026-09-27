package dev.margintrace.margin_attribution_backend.report;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/report-studio/onlyoffice")
@RequiredArgsConstructor
public class OnlyOfficeController {

    private static final String ACCESS_PURPOSE = "report-studio-default-document";

    private final OnlyOfficeJwtSigner jwtSigner;
    private final ReportDocumentStore documentStore;
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
    public Map<String, Object> config() throws Exception {
        documentStore.loadOrCreateDefaultDocument();
        String accessToken = jwtSigner.accessToken(ACCESS_PURPOSE);
        String baseBackendUrl = withoutTrailingSlash(backendInternalUrl);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("fileType", "docx");
        document.put("key", "margintrace-report-" + documentStore.currentVersion());
        document.put("title", "Management Commentary.docx");
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
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Management Commentary.docx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    @PostMapping("/callback")
    public Map<String, Integer> callback(
            @RequestParam String accessToken,
            @RequestBody Map<String, Object> callback) throws Exception {
        verifyAccessToken(accessToken);
        int status = callback.get("status") instanceof Number number ? number.intValue() : 0;

        if ((status == 2 || status == 6) && callback.get("url") instanceof String documentUrl) {
            URI uri = URI.create(documentUrl);
            verifyOnlyOfficeDownloadUrl(uri);
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

    private void verifyOnlyOfficeDownloadUrl(URI documentUri) {
        URI configuredUri = URI.create(onlyOfficeInternalUrl);
        if (!Objects.equals(documentUri.getHost(), configuredUri.getHost())) {
            throw new IllegalArgumentException("Unexpected ONLYOFFICE document download host");
        }
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
