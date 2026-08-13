package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.AccountReceivableLine;
import dev.margintrace.margin_attribution_backend.warehouse.model.AccountReceivableLineId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AccountReceivableLineRepository extends JpaRepository<AccountReceivableLine, AccountReceivableLineId> {
    Optional<AccountReceivableLine> findByAccountReceivableNoAndSalesOrderNoAndProductNo(
            String accountReceivableNo,
            String salesOrderNo,
            String productNo
    );

    List<AccountReceivableLine> findAllByAccountReceivableNo(String accountReceivableNo);

    List<AccountReceivableLine> findAllBySalesOrderNo(String salesOrderNo);

    List<AccountReceivableLine> findAllByProductNo(String productNo);
}
