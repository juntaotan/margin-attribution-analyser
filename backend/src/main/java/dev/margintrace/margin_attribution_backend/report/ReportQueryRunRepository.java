package dev.margintrace.margin_attribution_backend.report;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportQueryRunRepository extends JpaRepository<ReportQueryRunEntity, UUID> {

    List<ReportQueryRunEntity> findByPlaceholderConfigIdOrderByExecutedAtDesc(UUID placeholderConfigId);

    Optional<ReportQueryRunEntity> findFirstByPlaceholderConfigIdOrderByExecutedAtDesc(UUID placeholderConfigId);
}
