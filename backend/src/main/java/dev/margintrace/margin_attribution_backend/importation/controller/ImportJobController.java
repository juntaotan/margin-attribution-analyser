package dev.margintrace.margin_attribution_backend.importation.controller;

import dev.margintrace.margin_attribution_backend.importation.service.ImportJobStateService;
import dev.margintrace.margin_attribution_backend.importation.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/imports")
@RequiredArgsConstructor
public class ImportJobController {
    private final ImportService importService;
    private final ImportJobStateService stateService;

    /**
     * Imports a file and processes it based on the provided mapping table name 
     * and mapping result. Therefore, the mapping job is executed in the frontend
     * and returns the mapping result to the backend for further processing.
     * @param file
     * @param mappingTableName
     * @param mappingResult
     * @return
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportJobResponse> importFile(
        @RequestParam("file") MultipartFile file,
        @RequestParam("mappingTableName") String mappingTableName,
        @RequestParam(value = "mappingResult", defaultValue = "true") boolean mappingResult
    ) {
        if (!mappingResult) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Long jobId = importService.importer(file, mappingTableName, mappingResult);
            return ResponseEntity.accepted().body(ImportJobResponse.from(stateService.getRequiredJob(jobId)));
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{jobId}")
    public ImportJobResponse getJob(@PathVariable Long jobId) {
        try {
            return ImportJobResponse.from(stateService.getRequiredJob(jobId));
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    @GetMapping
    public ImportJobPageResponse listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ImportJobPageResponse.from(stateService.listJobs(page, size));
    }
}
