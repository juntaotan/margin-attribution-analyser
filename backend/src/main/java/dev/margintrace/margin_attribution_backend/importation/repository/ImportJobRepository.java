package dev.margintrace.margin_attribution_backend.importation.repository;

import dev.margintrace.margin_attribution_backend.importation.model.ImportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Page<ImportJob> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
