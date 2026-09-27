package dev.margintrace.margin_attribution_backend.importation.controller;

import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import org.springframework.data.domain.Page;
import java.util.List;

public record ImportJobPageResponse(
        List<ImportJobResponse> items, int page, int size, long totalElements, int totalPages
) {
    public static ImportJobPageResponse from(Page<ImportJob> jobs) {
        return new ImportJobPageResponse(
                jobs.getContent().stream().map(ImportJobResponse::from).toList(),
                jobs.getNumber(), jobs.getSize(), jobs.getTotalElements(), jobs.getTotalPages()
        );
    }
}
