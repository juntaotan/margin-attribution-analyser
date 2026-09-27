package dev.margintrace.margin_attribution_backend.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportPlaceholderConfigRepository extends JpaRepository<ReportPlaceholderConfigEntity, UUID> {

    Optional<ReportPlaceholderConfigEntity> findByDocumentIdAndControlTag(String documentId, String controlTag);

    List<ReportPlaceholderConfigEntity> findByDocumentId(String documentId);

    List<ReportPlaceholderConfigEntity> findByDocumentIdAndControlTagIn(String documentId, Collection<String> controlTags);
}
