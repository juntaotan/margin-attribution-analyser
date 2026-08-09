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
 * A component line in a bill of material.
 *
 * <p>The grain of this table is one material used by one product in one BOM.</p>
 */
@Entity
@Table(
        name = "bill_of_material",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bom_product_material",
                columnNames = {"bom_no", "product_no", "material_no"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillOfMaterial {
    private static final int BUSINESS_KEY_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bom_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String bomNo;

    @Column(name = "product_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String productNo;

    @Column(name = "material_no", nullable = false, length = BUSINESS_KEY_MAX_LENGTH)
    private String materialNo;

    @Column(name = "material_usage", nullable = false, precision = 18, scale = 6)
    private BigDecimal materialUsage;

    public static BillOfMaterial of(
            String bomNo,
            String productNo,
            String materialNo,
            BigDecimal materialUsage
    ) {
        BillOfMaterial line = new BillOfMaterial();
        line.bomNo = requireBusinessKey(bomNo, "BOM number");
        line.productNo = requireBusinessKey(productNo, "Product number");
        line.materialNo = requireBusinessKey(materialNo, "Material number");

        if (materialUsage == null || materialUsage.signum() <= 0) {
            throw new IllegalArgumentException("Material usage must be greater than zero");
        }
        line.materialUsage = materialUsage;
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
