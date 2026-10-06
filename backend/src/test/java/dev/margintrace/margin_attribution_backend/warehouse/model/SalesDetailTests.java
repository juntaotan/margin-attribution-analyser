package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SalesDetailTests {

    @Test
    void createsAValidSalesDetailAndNormalizesKeys() {
        SalesDetail detail = SalesDetail.of(
                LocalDate.of(2026, 1, 15),
                " COGS-001 ",
                " PRODUCT-001 ",
                new BigDecimal("8.500000"),
                new BigDecimal("3000.000000"),
                new BigDecimal("1700.000000")
        );

        assertThat(detail.getDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(detail.getMovementNo()).isEqualTo("COGS-001");
        assertThat(detail.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(detail.getProductQuantity()).isEqualByComparingTo("8.500000");
        assertThat(detail.getTotalRevenue()).isEqualByComparingTo("3000.000000");
        assertThat(detail.getTotalCost()).isEqualByComparingTo("1700.000000");
    }

    @Test
    void rejectsNonPositiveProductQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesDetail.of(
                LocalDate.of(2026, 1, 15), "COGS-001", "PRODUCT-001",
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN
        ));
    }

    @Test
    void rejectsNegativeTotalCost() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesDetail.of(
                LocalDate.of(2026, 1, 15), "COGS-001", "PRODUCT-001",
                BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("-0.01")
        ));
    }

    @Test
    void rejectsNegativeRevenue() {
        assertThatIllegalArgumentException().isThrownBy(() -> SalesDetail.of(
                LocalDate.of(2026, 1, 15), "SALE-001", "PRODUCT-001",
                BigDecimal.ONE, new BigDecimal("-0.01"), BigDecimal.TEN
        ));
    }
}
