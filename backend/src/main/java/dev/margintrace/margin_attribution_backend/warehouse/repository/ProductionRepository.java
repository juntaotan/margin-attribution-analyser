package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductionRepository extends JpaRepository<Production, Long> {
    Optional<Production> findByProductionOrderNoAndProductNo(
            String productionOrderNo,
            String productNo
    );

    List<Production> findAllByBomNo(String bomNo);

    List<Production> findAllByDepartment(String department);
}
