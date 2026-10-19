package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsageId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryUsageRepository extends JpaRepository<InventoryUsage, InventoryUsageId> {
    List<InventoryUsage> findAllByMovementNo(String movementNo);

    List<InventoryUsage> findAllByProductNo(String productNo);

    List<InventoryUsage> findAllByOrderNo(String orderNo);

    /** All available candidate receivers of a material; selection dates do not restrict lineage evidence. */
    List<InventoryUsage> findAllByMaterialNo(String materialNo);

    List<InventoryUsage> findAllByDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);

    List<InventoryUsage> findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(
            String productNo, LocalDate startDate, LocalDate endDate);

    List<InventoryUsage> findAllByProductNoAndDateBetweenAndMaterialNoIsNotNullOrderByDateAscIdAsc(
            String productNo, LocalDate startDate, LocalDate endDate);
}
