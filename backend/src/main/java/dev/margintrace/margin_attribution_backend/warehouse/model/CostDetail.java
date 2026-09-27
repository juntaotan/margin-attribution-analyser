package dev.margintrace.margin_attribution_backend.warehouse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A product cost line associated with a sales order. */
@Entity
@IdClass(CostDetailId.class)
@Table(
        name = "cost_details",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_cost_details_sale_movement_product",
                columnNames = {"sale_order_no", "movement_no", "product_no", "date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CostDetail {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String salesOrderNo;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "movement_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String movementNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal productQuantity;

    @Column(name = "total_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal totalCost;

    public static CostDetail of(
            String salesOrderNo,
            LocalDate date,
            String movementNo,
            String productNo,
            BigDecimal productQuantity,
            BigDecimal totalCost
    ) {
        CostDetail detail = new CostDetail();
        detail.salesOrderNo = requireBusinessKey(salesOrderNo, "Sales order number");
        detail.date = requireDate(date);
        detail.movementNo = requireBusinessKey(movementNo, "Movement number");
        detail.productNo = requireBusinessKey(productNo, "Product number");

        if (productQuantity == null || productQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Product quantity must be greater than zero");
        }
        if (totalCost == null || totalCost.signum() < 0) {
            throw new IllegalArgumentException("Total cost must not be negative");
        }

        detail.productQuantity = productQuantity;
        detail.totalCost = totalCost;
        return detail;
    }

    private static LocalDate requireDate(LocalDate value) {
        if (value == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        return value;
    }

    private static String requireBusinessKey(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        String normalized = value.trim();
        if (normalized.length() > BUSINESS_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(fieldName + " must not exceed 100 characters");
        }
        return normalized;
    }
}
