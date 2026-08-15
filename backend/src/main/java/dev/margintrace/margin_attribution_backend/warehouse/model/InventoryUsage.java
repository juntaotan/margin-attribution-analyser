package dev.margintrace.margin_attribution_backend.warehouse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An inventory movement attributed to a production order and product. */
@Entity
@IdClass(InventoryUsageId.class)
@Table(name = "inventory_usage")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryUsage {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "movement_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String movementNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", precision = 18, scale = 6)
    private BigDecimal productNum;

    @Column(name = "product_total_cost", precision = 18, scale = 6)
    private BigDecimal productTotalCost;

    @Column(name = "order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String orderNo;

    public static InventoryUsage of(
            LocalDate date,
            String movementNo,
            String productNo,
            BigDecimal productNum,
            BigDecimal productTotalCost,
            String orderNo
    ) {
        InventoryUsage usage = new InventoryUsage();
        if (date == null) {
            throw new IllegalArgumentException("Date must not be null");
        }
        usage.date = date;
        usage.movementNo = requireBusinessKey(movementNo, "Movement number");
        usage.productNo = requireBusinessKey(productNo, "Product number");
        usage.productNum = productNum;
        usage.productTotalCost = productTotalCost;
        usage.orderNo = requireBusinessKey(orderNo, "Order number");
        return usage;
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
