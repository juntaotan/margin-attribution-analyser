package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MaterialConsumptionTests {

    @Test
    void createsAValidMaterialConsumptionLineAndNormalizesKeys() {
        MaterialConsumption consumption = MaterialConsumption.of(
                " MC-001 ",
                " PRODUCTION-001 ",
                " PRODUCT-001 ",
                " MATERIAL-001 ",
                new BigDecimal("12.500000"),
                new BigDecimal("875.250000")
        );

        assertThat(consumption.getMaterialConsumptionNo()).isEqualTo("MC-001");
        assertThat(consumption.getProductionOrderNo()).isEqualTo("PRODUCTION-001");
        assertThat(consumption.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(consumption.getMaterialNo()).isEqualTo("MATERIAL-001");
        assertThat(consumption.getIssuedQuantity()).isEqualByComparingTo("12.500000");
        assertThat(consumption.getIssuedTotalCost()).isEqualByComparingTo("875.250000");
    }

    @Test
    void rejectsBlankMaterialConsumptionNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialConsumption.of(
                " ",
                "PRODUCTION-001",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsBlankProductionOrderNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialConsumption.of(
                "MC-001",
                " ",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsBlankProductNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialConsumption.of(
                "MC-001",
                "PRODUCTION-001",
                " ",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNonPositiveIssuedQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialConsumption.of(
                "MC-001",
                "PRODUCTION-001",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ZERO,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNegativeIssuedTotalCost() {
        assertThatIllegalArgumentException().isThrownBy(() -> MaterialConsumption.of(
                "MC-001",
                "PRODUCTION-001",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                new BigDecimal("-0.01")
        ));
    }
}
