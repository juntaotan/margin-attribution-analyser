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

/**
 * REST endpoints for submitting spreadsheet imports and following their jobs. Imports run in the background;
 * clients poll the job until it reaches a terminal status.
 */
@RestController
@RequestMapping("/imports")
@RequiredArgsConstructor
public class ImportJobController {
    private final ImportService importService;
    private final ImportJobStateService stateService;

    /**
     * Starts an import of a spreadsheet into the confirmed target table. The frontend recognises the table and the
     * user confirms it; the file is then validated and stored, and transformation continues in the background.
     *
     * @param file the XLSX or XLS file
     * @param mappingTableName the target table or one of its aliases
     * @param mappingResult whether the user confirmed the target table; must be true
     * @return HTTP 202 with the created job, or HTTP 400 without a body if the mapping was not confirmed
     * @throws org.springframework.web.server.ResponseStatusException with status 400 if the file or table name is
     *     invalid
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportJobResponse> importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("mappingTableName") String mappingTableName,
            @RequestParam(value = "mappingResult", defaultValue = "true") boolean mappingResult) {
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

    /**
     * @param jobId the import job
     * @return the job's status, stage and progress
     * @throws org.springframework.web.server.ResponseStatusException with status 404 if the job does not exist
     */
    @GetMapping("/{jobId}")
    public ImportJobResponse getJob(@PathVariable Long jobId) {
        try {
            return ImportJobResponse.from(stateService.getRequiredJob(jobId));
        } catch (IllegalArgumentException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }

    /**
     * @param page the zero-based page number; negative values are treated as 0
     * @param size the page size, limited to 1 to 100
     * @return the jobs, newest first
     */
    @GetMapping
    public ImportJobPageResponse listJobs(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ImportJobPageResponse.from(stateService.listJobs(page, size));
    }
}
