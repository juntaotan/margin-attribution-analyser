package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.AccountPayableLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountPayableLineRepository extends JpaRepository<AccountPayableLine, Long> {
    Optional<AccountPayableLine> findByAccountPayableNoAndPurchaseOrderNoAndMaterialNo(
            String accountPayableNo,
            String purchaseOrderNo,
            String materialNo
    );

    List<AccountPayableLine> findAllByAccountPayableNo(String accountPayableNo);

    List<AccountPayableLine> findAllByPurchaseOrderNo(String purchaseOrderNo);

    List<AccountPayableLine> findAllByMaterialNo(String materialNo);
}
