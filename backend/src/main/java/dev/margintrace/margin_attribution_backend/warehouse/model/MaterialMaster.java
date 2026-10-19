package dev.margintrace.margin_attribution_backend.warehouse.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One material code, with all descriptive fields optional. */
@Entity
@Table(name = "material_master")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MaterialMaster {
    @Id
    @Column(name = "material_no", nullable = false, length = 100)
    private String materialNo;

    @Column(name = "material_name", length = 200)
    private String materialName;

    @Column(name = "material_type", length = 30)
    private String materialType;

    @Column(name = "base_uom", length = 16)
    private String baseUom;

    @Column(name = "specification", length = 255)
    private String specification;

    @Column(name = "material_group", length = 100)
    private String materialGroup;

    @Column(name = "status", length = 20)
    private String status;

    /** Declared supplier for this material's embedding context; absent for internal production. */
    @Column(name = "supplier", length = 200)
    private String supplier;

    /** Explicit embedding source kind: PURCHASED or INTERNAL; missing values remain unknown. */
    @Column(name = "source_kind", length = 16)
    private String sourceKind;

    /** Declared embedding unit cost per baseUom, interpreted with the run's currency and valuation basis. */
    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    /**
     * @param materialNo the material number
     * @return a new material with only its trimmed number set
     * @throws IllegalArgumentException if the number is blank or longer than 100 characters
     */
    public static MaterialMaster of(String materialNo) {
        if (materialNo == null || materialNo.isBlank()) {
            throw new IllegalArgumentException("Material number must not be blank");
        }
        String normalized = materialNo.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("Material number must not exceed 100 characters");
        }
        MaterialMaster material = new MaterialMaster();
        material.materialNo = normalized;
        return material;
    }
}
