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
 * A material issued for production.
 *
 * <p>The grain is one material line on one production material consumption document.</p>
 */
@Entity
@Table(
        name = "material_consumption",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_material_consumption_line",
                columnNames = {"material_consumption_no", "material_no"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialConsumption {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_consumption_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String materialConsumptionNo;

    @Column(name = "material_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String materialNo;

    @Column(name = "material_num", nullable = false, precision = 18, scale = 6)
    private BigDecimal issuedQuantity;

    @Column(name = "material_total_cost", nullable = false, precision = 18, scale = 6)
    private BigDecimal issuedTotalCost;

    public static MaterialConsumption of(
            String materialConsumptionNo,
            String materialNo,
            BigDecimal issuedQuantity,
            BigDecimal issuedTotalCost
    ) {
        MaterialConsumption consumption = new MaterialConsumption();
        consumption.materialConsumptionNo = requireBusinessKey(
                materialConsumptionNo,
                "Material consumption number"
        );
        consumption.materialNo = requireBusinessKey(materialNo, "Material number");

        if (issuedQuantity == null || issuedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Issued quantity must be greater than zero");
        }
        if (issuedTotalCost == null || issuedTotalCost.signum() < 0) {
            throw new IllegalArgumentException("Issued total cost must not be negative");
        }

        consumption.issuedQuantity = issuedQuantity;
        consumption.issuedTotalCost = issuedTotalCost;
        return consumption;
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
