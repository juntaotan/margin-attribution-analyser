package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SalesOrderLineTests {

    @Test
    void createsAValidSalesOrderLineAndNormalizesKeys() {
        SalesOrderLine line = SalesOrderLine.of(
                " SO-001 ",
                " PRODUCT-001 ",
                new BigDecimal("8.500000"),
                new BigDecimal("2125.000000")
        );

        assertThat(line.getSalesOrderNo()).isEqualTo("SO-001");
        assertThat(line.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(line.getProductQuantity()).isEqualByComparingTo("8.500000");
        assertThat(line.getLineTotalSalesAmount()).isEqualByComparingTo("2125.000000");
    }

    @Test
    void rejectsBlankSalesOrderNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesOrderLine.of(
                " ",
                "PRODUCT-001",
                BigDecimal.ONE,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNonPositiveProductQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesOrderLine.of(
                "SO-001",
                "PRODUCT-001",
                BigDecimal.ZERO,
                BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNegativeLineTotalSalesAmount() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesOrderLine.of(
                "SO-001",
                "PRODUCT-001",
                BigDecimal.ONE,
                new BigDecimal("-0.01")
        ));
    }
}
