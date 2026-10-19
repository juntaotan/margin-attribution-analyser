package dev.margintrace.margin_attribution_backend.importation.controller;

import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import java.util.List;
import org.springframework.data.domain.Page;

public record ImportJobPageResponse(
        List<ImportJobResponse> items, int page, int size, long totalElements, int totalPages) {
    public static ImportJobPageResponse from(Page<ImportJob> jobs) {
        return new ImportJobPageResponse(
                jobs.getContent().stream().map(ImportJobResponse::from).toList(),
                jobs.getNumber(),
                jobs.getSize(),
                jobs.getTotalElements(),
                jobs.getTotalPages());
    }
}
