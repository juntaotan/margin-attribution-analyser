package dev.margintrace.margin_attribution_backend.importation.mapping;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMappingPresetCatalogTests {
    private final SchemaMappingPresetCatalog catalog = new SchemaMappingPresetCatalog();

    @Test
    void definesAPresetForEverySupportedDataSet() {
        assertThat(catalog.getAll())
                .extracting(DataSetDefinition::type)
                .containsExactlyInAnyOrder(DataSetType.values());
    }

    @Test
    void salesPresetMatchesTheCanonicalDatabaseSchema() {
        DataSetDefinition sales = catalog.getRequired(DataSetType.SALES);

        assertThat(sales.tableName()).isEqualTo("sales");
        assertThat(sales.fields())
                .extracting(TargetFieldDefinition::columnName)
                .containsExactly(
                        "sale_order_no",
                        "product_no",
                        "product_num",
                        "product_unit_price",
                        "product_total_price",
                        "movement_no"
                );
        assertThat(sales.requireField("saleOrderNo").required()).isTrue();
        assertThat(sales.requireField("productTotalPrice").dataType()).isEqualTo(DataType.NUMERIC);
        assertThat(sales.requireField("saleOrderNo").aliases())
                .contains("saleOrderNo", "sale_order_no", "销售订单号");
    }

    @Test
    void allPresetDefinitionsHaveUniqueFieldAndColumnNames() {
        for (DataSetDefinition definition : catalog.getAll()) {
            assertThat(definition.fields())
                    .extracting(TargetFieldDefinition::fieldKey)
                    .doesNotHaveDuplicates();
            assertThat(definition.fields())
                    .extracting(TargetFieldDefinition::columnName)
                    .doesNotHaveDuplicates();
        }

        assertThat(catalog.getAll()).hasSize(Arrays.stream(DataSetType.values()).toList().size());
    }
}
