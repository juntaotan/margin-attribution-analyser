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
import java.time.LocalDate;

/**
 * A product line on a sales order.
 *
 * <p>The grain is one product per sales order.</p>
 */
@Entity
@Table(
        name = "sales_order",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_sale_order_product",
                columnNames = {"sale_order_no", "product_no"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SalesOrderLine {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String salesOrderNo;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "movement_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String movementNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal productQuantity;

    /** Total sales amount represented by this product line. */
    @Column(name = "product_total_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lineTotalSalesAmount;

    public static SalesOrderLine of(
            String salesOrderNo,
            LocalDate date,
            String movementNo,
            String productNo,
            BigDecimal productQuantity,
            BigDecimal lineTotalSalesAmount
    ) {
        SalesOrderLine line = new SalesOrderLine();
        line.salesOrderNo = requireBusinessKey(salesOrderNo, "Sales order number");
        line.date = requireDate(date);
        line.movementNo = requireBusinessKey(movementNo, "Movement number");
        line.productNo = requireBusinessKey(productNo, "Product number");

        if (productQuantity == null || productQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Product quantity must be greater than zero");
        }
        if (lineTotalSalesAmount == null || lineTotalSalesAmount.signum() < 0) {
            throw new IllegalArgumentException("Line total sales amount must not be negative");
        }

        line.productQuantity = productQuantity;
        line.lineTotalSalesAmount = lineTotalSalesAmount;
        return line;
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
