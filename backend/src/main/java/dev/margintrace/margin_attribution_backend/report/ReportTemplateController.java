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

/**
 * REST endpoints for viewing, downloading, uploading and resetting the management-report master template.
 */
@RestController
@RequestMapping("/api/v1/settings/template")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ReportTemplateController {

    private final ReportTemplateStore templateStore;

    /**
     * @return the template's file details and placeholder readiness
     * @throws Exception if the template cannot be loaded
     */
    @GetMapping
    public ReportTemplateStore.TemplateMetadata getMetadata() throws Exception {
        return templateStore.getMetadata();
    }

    /**
     * @param file the new master template
     * @return the metadata of the uploaded template
     * @throws IllegalArgumentException if the file is not a valid DOCX document
     * @throws Exception if the template cannot be stored
     */
    @PostMapping("/upload")
    public ReportTemplateStore.TemplateMetadata uploadTemplate(@RequestParam("file") MultipartFile file)
            throws Exception {
        return templateStore.uploadMasterTemplate(file.getOriginalFilename(), file.getBytes());
    }

    /**
     * @return the master template as a DOCX attachment
     * @throws Exception if the template cannot be loaded
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadTemplate() throws Exception {
        ReportTemplateStore.TemplateMetadata meta = templateStore.getMetadata();
        byte[] content = templateStore.loadOrCreateMasterTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + meta.filename() + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .contentLength(content.length)
                .body(content);
    }

    /**
     * @return the metadata of the restored built-in template
     * @throws Exception if the template cannot be stored
     */
    @PostMapping("/reset")
    public ReportTemplateStore.TemplateMetadata resetTemplate() throws Exception {
        return templateStore.resetToDefault();
    }
}
