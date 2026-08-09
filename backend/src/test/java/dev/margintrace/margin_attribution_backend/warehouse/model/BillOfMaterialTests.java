package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BillOfMaterialTests {

    @Test
    void createsAValidBomLineAndNormalizesBusinessKeys() {
        BillOfMaterial line = BillOfMaterial.of(
                " BOM-001 ",
                " PRODUCT-001 ",
                " MATERIAL-001 ",
                new BigDecimal("1.250000")
        );

        assertThat(line.getBomNo()).isEqualTo("BOM-001");
        assertThat(line.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(line.getMaterialNo()).isEqualTo("MATERIAL-001");
        assertThat(line.getMaterialUsage()).isEqualByComparingTo("1.250000");
    }

    @Test
    void rejectsBlankBusinessKeys() {
        assertThatIllegalArgumentException().isThrownBy(() -> BillOfMaterial.of(
                " ",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ONE
        ));
    }

    @Test
    void rejectsNonPositiveMaterialUsage() {
        assertThatIllegalArgumentException().isThrownBy(() -> BillOfMaterial.of(
                "BOM-001",
                "PRODUCT-001",
                "MATERIAL-001",
                BigDecimal.ZERO
        ));
    }
}
