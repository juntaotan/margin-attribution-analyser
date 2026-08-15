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
 * A purchased material line recorded on an accounts-payable document.
 *
 * <p>The grain is one material from one purchase order per payable document.</p>
 */
@Entity
@IdClass(AccountPayableLineId.class)
@Table(
        name = "account_payables",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_account_payable_purchase_material",
                columnNames = {"account_payable_no", "purchase_order_no", "product_no", "date"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccountPayableLine {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_payable_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String accountPayableNo;

    @Id
    @Column(name = "date", nullable = false)
    private LocalDate date;

    /** The purchased material code stored in the core schema's product column. */
    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String materialNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal materialQuantity;

    /** Total purchase cost represented by this material line. */
    @Column(name = "product_total_price", nullable = false, precision = 18, scale = 6)
    private BigDecimal lineTotalCost;

    @Column(name = "purchase_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String purchaseOrderNo;

    public static AccountPayableLine of(
            String accountPayableNo,
            LocalDate date,
            String materialNo,
            BigDecimal materialQuantity,
            BigDecimal lineTotalCost,
            String purchaseOrderNo
    ) {
        AccountPayableLine line = new AccountPayableLine();
        line.accountPayableNo = requireBusinessKey(accountPayableNo, "Account payable number");
        line.date = requireDate(date);
        line.materialNo = requireBusinessKey(materialNo, "Material number");
        line.purchaseOrderNo = requireBusinessKey(purchaseOrderNo, "Purchase order number");

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
