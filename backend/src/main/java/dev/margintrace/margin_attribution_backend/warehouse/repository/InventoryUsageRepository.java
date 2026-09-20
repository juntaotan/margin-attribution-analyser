package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsageId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface InventoryUsageRepository extends JpaRepository<InventoryUsage, InventoryUsageId> {
    List<InventoryUsage> findAllByMovementNo(String movementNo);

    List<InventoryUsage> findAllByProductNo(String productNo);

    List<InventoryUsage> findAllByOrderNo(String orderNo);
    List<InventoryUsage> findAllByDateBetweenOrderByDateAsc(
        LocalDate startDate, LocalDate endDate);

    List<InventoryUsage> findAllByProductNoAndDateBetweenAndMaterialNoIsNotNullOrderByDateAscIdAsc(
            String productNo,
            LocalDate startDate,
            LocalDate endDate
    );
}
