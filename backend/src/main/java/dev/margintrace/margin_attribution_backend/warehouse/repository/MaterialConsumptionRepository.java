package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.MaterialConsumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MaterialConsumptionRepository extends JpaRepository<MaterialConsumption, Long> {
    Optional<MaterialConsumption> findByMaterialConsumptionNoAndMaterialNo(
            String materialConsumptionNo,
            String materialNo
    );

    List<MaterialConsumption> findAllByMaterialConsumptionNo(String materialConsumptionNo);

    List<MaterialConsumption> findAllByMaterialNo(String materialNo);
}
