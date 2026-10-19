package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.PurchaseOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Queries over purchase order lines.
 */
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {
    Optional<PurchaseOrderLine> findByPurchaseOrderNoAndMaterialNo(String purchaseOrderNo, String materialNo);

    List<PurchaseOrderLine> findAllByPurchaseOrderNo(String purchaseOrderNo);

    List<PurchaseOrderLine> findAllByMaterialNo(String materialNo);
}
