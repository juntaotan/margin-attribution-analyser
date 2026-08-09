package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.PurchaseOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, Long> {
    Optional<PurchaseOrderLine> findByPurchaseOrderNoAndMaterialNo(
            String purchaseOrderNo,
            String materialNo
    );

    List<PurchaseOrderLine> findAllByPurchaseOrderNo(String purchaseOrderNo);

    List<PurchaseOrderLine> findAllByMaterialNo(String materialNo);
}
