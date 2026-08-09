package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SalesOrderLineRepository extends JpaRepository<SalesOrderLine, Long> {
    Optional<SalesOrderLine> findBySalesOrderNoAndProductNo(
            String salesOrderNo,
            String productNo
    );

    List<SalesOrderLine> findAllBySalesOrderNo(String salesOrderNo);

    List<SalesOrderLine> findAllByProductNo(String productNo);
}
