package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AccountReceivableLineTests {

    @Test
    void createsAValidAccountReceivableLineAndNormalizesKeys() {
        AccountReceivableLine line = AccountReceivableLine.of(
                " AR-001 ",
                LocalDate.of(2026, 8, 13),
                " PRODUCT-001 ",
                new BigDecimal("8.500000"),
                new BigDecimal("2125.000000"),
                " SO-001 "
        );

        assertThat(line.getAccountReceivableNo()).isEqualTo("AR-001");
        assertThat(line.getDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(line.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(line.getProductQuantity()).isEqualByComparingTo("8.500000");
        assertThat(line.getLineTotalSalesAmount()).isEqualByComparingTo("2125.000000");
        assertThat(line.getSalesOrderNo()).isEqualTo("SO-001");
    }

    @Test
    void rejectsBlankAccountReceivableNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountReceivableLine.of(
                " ",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ONE,
                BigDecimal.TEN,
                "SO-001"
        ));
    }

    @Test
    void rejectsBlankSalesOrderNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountReceivableLine.of(
                "AR-001",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ONE,
                BigDecimal.TEN,
                " "
        ));
    }

    @Test
    void rejectsNonPositiveProductQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountReceivableLine.of(
                "AR-001",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ZERO,
                BigDecimal.TEN,
                "SO-001"
        ));
    }

    @Test
    void rejectsNegativeLineTotalSalesAmount() {
        assertThatIllegalArgumentException().isThrownBy(() -> AccountReceivableLine.of(
                "AR-001",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ONE,
                new BigDecimal("-0.01"),
                "SO-001"
        ));
    }
}
