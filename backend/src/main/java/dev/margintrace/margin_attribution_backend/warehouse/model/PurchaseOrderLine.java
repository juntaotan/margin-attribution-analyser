package dev.margintrace.margin_attribution_backend.warehouse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A material line on a purchase order.
 *
 * <p>The grain is one material per purchase order.</p>
 */
@Entity
@Table(
        name = "purchases",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_purchase_order_product",
                columnNames = {"purchase_order_no", "product_no"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrderLine {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "purchase_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String purchaseOrderNo;

    /**
     * The core schema calls this column {@code product_no}; in the purchasing
     * warehouse model it represents the purchased material code.
     */
    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String materialNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal materialQuantity;

    /** Total purchase cost for this material line, rather than the whole order. */
    @Column(name = "product_total_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lineTotalCost;

    public static PurchaseOrderLine of(
            String purchaseOrderNo,
            String materialNo,
            BigDecimal materialQuantity,
            BigDecimal lineTotalCost
    ) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.purchaseOrderNo = requireBusinessKey(purchaseOrderNo, "Purchase order number");
        line.materialNo = requireBusinessKey(materialNo, "Material number");

        if (materialQuantity == null || materialQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Material quantity must be greater than zero");
        }
        if (lineTotalCost == null || lineTotalCost.signum() < 0) {
            throw new IllegalArgumentException("Line total cost must not be negative");
        }

        line.materialQuantity = materialQuantity;
        line.lineTotalCost = lineTotalCost;
        return line;
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
