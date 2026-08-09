package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AccountPayableLineTests {

    @Test
    void createsAValidAccountPayableLineAndNormalizesKeys() {
        AccountPayableLine line = AccountPayableLine.of(
                " AP-001 ",
                " MATERIAL-001 ",
                new BigDecimal("20.500000"),
                new BigDecimal("1435.000000"),
                " PO-001 "
        );

        assertThat(line.getAccountPayableNo()).isEqualTo("AP-001");
        assertThat(line.getMaterialNo()).isEqualTo("MATERIAL-001");
        assertThat(line.getMaterialQuantity()).isEqualByComparingTo("20.500000");
        assertThat(line.getLineTotalCost()).isEqualByComparingTo("1435.000000");
        assertThat(line.getPurchaseOrderNo()).isEqualTo("PO-001");
    }

    @Test
    void rejectsBlankAccountPayableNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountPayableLine.of(
                " ",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN,
                "PO-001"
        ));
    }

    @Test
    void rejectsBlankPurchaseOrderNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountPayableLine.of(
                "AP-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                BigDecimal.TEN,
                " "
        ));
    }

    @Test
    void rejectsNonPositiveMaterialQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountPayableLine.of(
                "AP-001",
                "MATERIAL-001",
                BigDecimal.ZERO,
                BigDecimal.TEN,
                "PO-001"
        ));
    }

    @Test
    void rejectsNegativeLineTotalCost() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountPayableLine.of(
                "AP-001",
                "MATERIAL-001",
                BigDecimal.ONE,
                new BigDecimal("-0.01"),
                "PO-001"
        ));
    }
}
