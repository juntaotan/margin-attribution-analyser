package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.MaterialMaster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialMasterRepository extends JpaRepository<MaterialMaster, String> {
}
