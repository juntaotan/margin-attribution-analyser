package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InventoryUsageTests {

    @Test
    void createsAValidInventoryUsageAndNormalizesKeys() {
        InventoryUsage usage = InventoryUsage.of(
                LocalDate.of(2026, 8, 13),
                " MOVE-001 ",
                " PRODUCT-001 ",
                new BigDecimal("-2.000000"),
                new BigDecimal("25.500000"),
                " PO-001 "
        );

        assertThat(usage.getDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(usage.getMovementNo()).isEqualTo("MOVE-001");
        assertThat(usage.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(usage.getProductNum()).isEqualByComparingTo("-2.000000");
        assertThat(usage.getProductTotalCost()).isEqualByComparingTo("25.500000");
        assertThat(usage.getOrderNo()).isEqualTo("PO-001");
    }

    @Test
    void acceptsNullableOptionalAmounts() {
        InventoryUsage usage = InventoryUsage.of(
                LocalDate.of(2026, 8, 13),
                "MOVE-001",
                "PRODUCT-001",
                null,
                null,
                "PO-001"
        );

        assertThat(usage.getProductNum()).isNull();
        assertThat(usage.getProductTotalCost()).isNull();
    }

    @Test
    void rejectsMissingRequiredValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> InventoryUsage.of(
                null,
                "MOVE-001",
                "PRODUCT-001",
                BigDecimal.ONE,
                BigDecimal.TEN,
                "PO-001"
        ));
    }
}
