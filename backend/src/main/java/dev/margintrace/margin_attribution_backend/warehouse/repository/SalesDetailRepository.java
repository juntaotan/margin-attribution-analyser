package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetail;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SalesDetailRepository extends JpaRepository<SalesDetail, SalesDetailId> {

    List<SalesDetail> findAllByDateBetweenOrderByDateAscIdAsc(LocalDate startDate, LocalDate endDate);

    @Query("""
            select sum(detail.totalCost)
            from SalesDetail detail
            where detail.date between :startDate and :endDate
            """)
    BigDecimal sumTotalCostBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
