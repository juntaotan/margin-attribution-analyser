package dev.margintrace.margin_attribution_backend.importation.handler;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SchemaMappingHandlerTests {
    private final SchemaMappingHandler handler = new SchemaMappingHandler(new SchemaMappingPresetCatalog());

    @Test
    void mapsAliasesUsingSelectedTargetRegardlessOfSheetName() {
        ImportContext context = context("Account Payables", Map.of(
                "Invoice Date", DataType.DATE,
                "AP No", DataType.TEXT,
                "Material Code", DataType.TEXT,
                "Billed Quantity", DataType.NUMERIC,
                "Invoice Amount", DataType.NUMERIC,
                "PO No", DataType.TEXT,
                "Notes", DataType.TEXT
        ));

        handler.doImport(context);

        assertThat(context.getDataSetDefinition().tableName()).isEqualTo("account_payables");
        assertThat(context.getColumnMappings())
                .extractingByKey("AP No")
                .extracting(TargetFieldDefinition::columnName)
                .isEqualTo("account_payable_no");
        assertThat(context.getColumnMappings().values())
                .extracting(TargetFieldDefinition::columnName)
                .containsExactlyInAnyOrder(
                        "date",
                        "account_payable_no",
                        "product_no",
                        "product_num",
                        "product_total_price",
                        "purchase_order_no"
                );
        assertThat(context.getColumnMappings()).doesNotContainKey("Notes");
    }

    @Test
    void rejectsMissingRequiredWarehouseMappings() {
        ImportContext context = context("sales", Map.of(
                "Sales Order No", DataType.TEXT,
                "SKU", DataType.TEXT
        ));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.doImport(context))
                .withMessageContaining("productQuantity", "lineTotalSalesAmount");
    }

    @Test
    void rejectsTwoSourceColumnsMappedToTheSameWarehouseColumn() {
        LinkedHashMap<String, DataType> sourceColumns = new LinkedHashMap<>();
        sourceColumns.put("Sales Order No", DataType.TEXT);
        sourceColumns.put("Sales Date", DataType.DATE);
        sourceColumns.put("Movement No", DataType.TEXT);
        sourceColumns.put("SKU", DataType.TEXT);
        sourceColumns.put("Product No", DataType.TEXT);
        sourceColumns.put("Quantity", DataType.NUMERIC);
        sourceColumns.put("Amount", DataType.NUMERIC);
        ImportContext context = context("sales", sourceColumns);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.doImport(context))
                .withMessageContaining("Multiple source columns map to target column 'product_no'");
    }

    @Test
    void rejectsATableWithoutAWarehousePreset() {
        ImportContext context = context("unknown_table", Map.of("id", DataType.NUMERIC));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> handler.doImport(context))
                .withMessageContaining("No schema-mapping preset is defined for table: unknown_table");
    }

    private static ImportContext context(String tableName, Map<String, DataType> sourceColumns) {
        ImportContext context = new ImportContext();
        context.setMappingTableName(tableName);
        context.setMappingResult(true);
        context.setTableStructure(new TableStructure("Unrelated sheet", 0, 0, sourceColumns.size() - 1, 1, 1.0));
        context.setColumnTypes(new LinkedHashMap<>(sourceColumns));
        return context;
    }

    @Test
    void rejectsUnconfirmedSelection() {
        ImportContext context = context("sales", Map.of("SKU", DataType.TEXT));
        context.setMappingResult(false);
        assertThatIllegalArgumentException().isThrownBy(() -> handler.doImport(context))
                .withMessageContaining("confirmed");
    }

    @Test
    void doesNotFallBackToSheetNameWhenTargetIsMissing() {
        ImportContext context = context(null, Map.of("SKU", DataType.TEXT));
        context.setTableStructure(new TableStructure("sales", 0, 0, 0, 1, 1.0));
        assertThatIllegalArgumentException().isThrownBy(() -> handler.doImport(context))
                .withMessageContaining("Table name must not be blank");
    }
}
