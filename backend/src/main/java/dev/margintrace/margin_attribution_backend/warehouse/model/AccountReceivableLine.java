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

/**
 * A sold product line recorded on an accounts-receivable document.
 *
 * <p>The grain is one product from one sales order per receivable document.</p>
 */
@Entity
@IdClass(AccountReceivableLineId.class)
@Table(
        name = "account_receivables",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_account_receivable_sale_product",
                columnNames = {"account_receivable_no", "sale_order_no", "product_no", "date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountReceivableLine {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_receivable_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String accountReceivableNo;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal productQuantity;

    /** Total sales amount represented by this product line. */
    @Column(name = "product_total_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lineTotalSalesAmount;

    @Column(name = "sale_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String salesOrderNo;

    public static AccountReceivableLine of(
            String accountReceivableNo,
            LocalDate date,
            String productNo,
            BigDecimal productQuantity,
            BigDecimal lineTotalSalesAmount,
            String salesOrderNo
    ) {
        AccountReceivableLine line = new AccountReceivableLine();
        line.accountReceivableNo = requireBusinessKey(
                accountReceivableNo,
                "Account receivable number"
        );
        line.date = requireDate(date);
        line.productNo = requireBusinessKey(productNo, "Product number");
        line.salesOrderNo = requireBusinessKey(salesOrderNo, "Sales order number");

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
