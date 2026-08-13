package dev.margintrace.margin_attribution_backend.warehouse.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProductionTests {

    @Test
    void createsAValidProductionResultAndNormalizesText() {
        Production production = Production.of(
                " PO-001 ",
                LocalDate.of(2026, 8, 13),
                " PRODUCT-001 ",
                new BigDecimal("100.000000"),
                " Manufacturing ",
                " BOM-001 "
        );

        assertThat(production.getProductionOrderNo()).isEqualTo("PO-001");
        assertThat(production.getDate()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(production.getProductNo()).isEqualTo("PRODUCT-001");
        assertThat(production.getCompletedQuantity()).isEqualByComparingTo("100.000000");
        assertThat(production.getDepartment()).isEqualTo("Manufacturing");
        assertThat(production.getBomNo()).isEqualTo("BOM-001");
    }

    @Test
    void rejectsNonPositiveCompletedQuantity() {
        assertThatIllegalArgumentException().isThrownBy(() -> Production.of(
                "PO-001",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ZERO,
                "Manufacturing",
                "BOM-001"
        ));
    }

    @Test
    void rejectsBlankBomNumber() {
        assertThatIllegalArgumentException().isThrownBy(() -> Production.of(
                "PO-001",
                LocalDate.of(2026, 8, 13),
                "PRODUCT-001",
                BigDecimal.ONE,
                "Manufacturing",
                " "
        ));
    }
}
