package dev.margintrace.margin_attribution_backend.report;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/settings/template")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReportTemplateController {

    private final ReportTemplateStore templateStore;

    @GetMapping
    public ReportTemplateStore.TemplateMetadata getMetadata() throws Exception {
        return templateStore.getMetadata();
    }

    @PostMapping("/upload")
    public ReportTemplateStore.TemplateMetadata uploadTemplate(
            @RequestParam("file") MultipartFile file) throws Exception {
        return templateStore.uploadMasterTemplate(file.getOriginalFilename(), file.getBytes());
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadTemplate() throws Exception {
        ReportTemplateStore.TemplateMetadata meta = templateStore.getMetadata();
        byte[] content = templateStore.loadOrCreateMasterTemplate();
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + meta.filename() + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    @PostMapping("/reset")
    public ReportTemplateStore.TemplateMetadata resetTemplate() throws Exception {
        return templateStore.resetToDefault();
    }
}
