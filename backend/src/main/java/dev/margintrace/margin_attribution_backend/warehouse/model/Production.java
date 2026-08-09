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
 * The completed output of a product on a production order.
 *
 * <p>The grain of this table is one completed product per production order.</p>
 */
@Entity
@Table(
        name = "production",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_production_product",
                columnNames = {"production_order_no", "product_no"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Production {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;
    private static final int DEPARTMENT_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "production_order_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productionOrderNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "product_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal completedQuantity;

    @Column(name = "product_department", nullable = false, length = DEPARTMENT_MAX_LENGTH)
    private String department;

    @Column(name = "bom_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String bomNo;

    public static Production of(
            String productionOrderNo,
            String productNo,
            BigDecimal completedQuantity,
            String department,
            String bomNo
    ) {
        Production production = new Production();
        production.productionOrderNo = requireText(
                productionOrderNo,
                "Production order number",
                BUSINESS_KEY_MAX_LENGTH
        );
        production.productNo = requireText(productNo, "Product number", BUSINESS_KEY_MAX_LENGTH);
        production.completedQuantity = requirePositive(completedQuantity, "Completed quantity");
        production.department = requireText(department, "Department", DEPARTMENT_MAX_LENGTH);
        production.bomNo = requireText(bomNo, "BOM number", BUSINESS_KEY_MAX_LENGTH);
        return production;
    }

    private static String requireText(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    fieldName + " must not exceed " + maximumLength + " characters"
            );
        }
        return normalized;
    }

    private static BigDecimal requirePositive(BigDecimal value, String fieldName) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return value;
    }

}
