package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import dev.margintrace.margin_attribution_backend.investigation.InvestigationConfiguration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Upload, list and delete knowledge documents (engineering changes, quality records and historical cases). */
@RestController
@RequestMapping("/api/v1/investigation/knowledge/documents")
@CrossOrigin(origins = "*")
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class KnowledgeController {
    private final KnowledgeIngestionService ingestion;
    private final KnowledgeRepository repository;

    public KnowledgeController(KnowledgeIngestionService ingestion, KnowledgeRepository repository) {
        this.ingestion = ingestion;
        this.repository = repository;
    }

    /** Stores and indexes one file; {@code effectiveDate} is the ISO date the document took effect or was recorded. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KnowledgeRepository.KnowledgeDocument upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceType") SourceType sourceType,
            @RequestParam("title") String title,
            @RequestParam("effectiveDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveDate)
            throws Exception {
        return ingestion.ingest(file, sourceType, title, effectiveDate);
    }

    /**
     * @return the stored documents, newest effective date first
     */
    @GetMapping
    public List<KnowledgeRepository.KnowledgeDocument> list() {
        return repository.findDocuments();
    }

    /**
     * Deletes a document with its chunks, vectors and stored file.
     *
     * @param id the document
     * @return HTTP 204
     * @throws IllegalArgumentException if the document does not exist
     * @throws Exception if the stored file cannot be removed
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) throws Exception {
        ingestion.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reports an invalid request as HTTP 400 with a {@code message}.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
    }

    /** The embedding runtime is not installed or failed. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", exception.getMessage()));
    }
}
