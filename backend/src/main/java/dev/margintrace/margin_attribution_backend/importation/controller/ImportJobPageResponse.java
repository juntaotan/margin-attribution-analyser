package dev.margintrace.margin_attribution_backend.importation.controller;

import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * One page of import jobs.
 */
public record ImportJobPageResponse(
        List<ImportJobResponse> items, int page, int size, long totalElements, int totalPages) {
    /**
     * @return the page converted to responses
     */
    public static ImportJobPageResponse from(Page<ImportJob> jobs) {
        return new ImportJobPageResponse(
                jobs.getContent().stream().map(ImportJobResponse::from).toList(),
                jobs.getNumber(),
                jobs.getSize(),
                jobs.getTotalElements(),
                jobs.getTotalPages());
    }
}
