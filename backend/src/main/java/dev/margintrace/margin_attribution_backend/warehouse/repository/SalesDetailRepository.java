package dev.margintrace.margin_attribution_backend.warehouse.repository;

import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetail;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetailId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Queries over sales lines.
 */
public interface SalesDetailRepository extends JpaRepository<SalesDetail, SalesDetailId> {

    List<SalesDetail> findAllByDateBetweenOrderByDateAscIdAsc(LocalDate startDate, LocalDate endDate);

    /**
     * @return the total cost of goods sold in the inclusive date range, or null if there are no sales
     */
    @Query(
            """
            select sum(detail.totalCost)
            from SalesDetail detail
            where detail.date between :startDate and :endDate
            """)
    BigDecimal sumTotalCostBetween(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);
}
