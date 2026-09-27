package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CostDetailTests {

    @Test
    void createsAValidCostDetailAndNormalizesKeys() {
        CostDetail detail = CostDetail.of(
                " SO-001 ",
                LocalDate.of(2026, 1, 15),
                " COGS-001 ",
                " PRODUCT-001 ",
                new BigDecimal("8.500000"),
                new BigDecimal("1700.000000")
        );

        assertThat(detail.getSalesOrderNo()).isEqualTo("SO-001");
        assertThat(detail.getDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(detail.getMovementNo()).isEqualTo("COGS-001");
        assertThat(detail.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(detail.getProductQuantity()).isEqualByComparingTo("8.500000");
        assertThat(detail.getTotalCost()).isEqualByComparingTo("1700.000000");
    }

    @Test
    void rejectsNonPositiveProductQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> CostDetail.of(
                "SO-001", LocalDate.of(2026, 1, 15), "COGS-001", "PRODUCT-001",
                BigDecimal.ZERO, BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNegativeTotalCost() {
        assertThatIllegalArgumentException().isThrownBy(() -> CostDetail.of(
                "SO-001", LocalDate.of(2026, 1, 15), "COGS-001", "PRODUCT-001",
                BigDecimal.ONE, new BigDecimal("-0.01")
        ));
    }
}
