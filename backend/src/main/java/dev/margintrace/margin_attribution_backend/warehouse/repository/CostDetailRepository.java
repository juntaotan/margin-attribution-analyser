package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.CostDetail;
import dev.margintrace.margin_attribution_backend.warehouse.model.CostDetailId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface CostDetailRepository extends JpaRepository<CostDetail, CostDetailId> {

    @Query("""
            select sum(detail.totalCost)
            from CostDetail detail
            where detail.date between :startDate and :endDate
            """)
    BigDecimal sumTotalCostBetween(
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
}
