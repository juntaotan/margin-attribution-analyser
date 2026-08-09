package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PurchaseOrderLineTests {

    @Test
    void createsAValidPurchaseOrderLineAndNormalizesKeys() {
        PurchaseOrderLine line = PurchaseOrderLine.of(
                " PO-001 ",
                " MATERIAL-001 ",
                new BigDecimal("20.500000"),
                new BigDecimal("1435.000000")
        );

        assertThat(line.getPurchaseOrderNo()).isEqualTo("PO-001");
        assertThat(line.getMaterialNo()).isEqualTo("MATERIAL-001");
        assertThat(line.getMaterialQuantity()).isEqualByComparingTo("20.500000");
        assertThat(line.getLineTotalCost()).isEqualByComparingTo("1435.000000");
    }

    @Test
    void rejectsBlankPurchaseOrderNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> PurchaseOrderLine.of(
                " ",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNonPositiveMaterialQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> PurchaseOrderLine.of(
                "PO-001",
                "MATERIAL-001",
                BigDecimal.ZERO,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNegativeLineTotalCost() {
        assertThatIllegalArgumentException().isThrownBy(() -> PurchaseOrderLine.of(
                "PO-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                new BigDecimal("-0.01")
        ));
    }
}
