package dev.margintrace.margin_attribution_backend.importation.mapping;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.warehouse.model.AccountPayableLine;
import dev.margintrace.margin_attribution_backend.warehouse.model.AccountReceivableLine;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.MaterialConsumption;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.model.PurchaseOrderLine;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesOrderLine;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

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

        assertThat(sales.tableName()).isEqualTo("sales_order");
        assertThat(sales.fields())
                .extracting(TargetFieldDefinition::columnName)
                .containsExactly(
                        "date",
                        "movement_no",
                        "sale_order_no",
                        "product_no",
                        "product_num",
                        "product_total_price"
                );
        assertThat(sales.requireField("salesOrderNo").required()).isTrue();
        assertThat(sales.requireField("lineTotalSalesAmount").dataType()).isEqualTo(DataType.NUMERIC);
        assertThat(sales.requireField("salesOrderNo").aliases())
                .contains("salesOrderNo", "saleOrderNo", "sale_order_no", "销售订单号");
    }

    @Test
    void accountPayablePresetMapsAccountPayableLineFieldsToPhysicalColumns() {
        DataSetDefinition accountPayables = catalog.getRequired(DataSetType.ACCOUNT_PAYABLE);

        assertThat(accountPayables.tableName()).isEqualTo("account_payables");
        assertThat(accountPayables.fields())
                .extracting(TargetFieldDefinition::fieldKey, TargetFieldDefinition::columnName)
                .containsExactly(
                        tuple("date", "date"),
                        tuple("accountPayableNo", "account_payable_no"),
                        tuple("materialNo", "product_no"),
                        tuple("materialQuantity", "product_num"),
                        tuple("lineTotalCost", "product_total_price"),
                        tuple("purchaseOrderNo", "purchase_order_no")
                );
        assertThat(accountPayables.fields()).allMatch(TargetFieldDefinition::required);
        assertThat(accountPayables.requireField("materialNo").aliases())
                .contains("materialNo", "product_no", "material no", "物料编号");
        assertThat(accountPayables.requireField("materialQuantity").dataType()).isEqualTo(DataType.NUMERIC);
        assertThat(accountPayables.requireField("lineTotalCost").aliases())
                .contains("lineTotalCost", "product_total_price", "payable amount", "应付金额");
    }

    @Test
    void warehousePresetsMatchEveryMappedEntityFieldAndColumn() {
        Map<DataSetType, Class<?>> warehouseEntities = Map.of(
                DataSetType.SALES, SalesOrderLine.class,
                DataSetType.ACCOUNT_RECEIVABLE, AccountReceivableLine.class,
                DataSetType.PURCHASE, PurchaseOrderLine.class,
                DataSetType.ACCOUNT_PAYABLE, AccountPayableLine.class,
                DataSetType.INVENTORY_MOVEMENT, InventoryUsage.class,
                DataSetType.PRODUCTION, Production.class,
                DataSetType.BOM, BillOfMaterial.class,
                DataSetType.MATERIAL_CONSUMPTION, MaterialConsumption.class
        );

        warehouseEntities.forEach((dataSetType, entityType) -> {
            DataSetDefinition definition = catalog.getRequired(dataSetType);
            Table table = entityType.getAnnotation(Table.class);

            assertThat(definition.tableName()).isEqualTo(table.name());
            assertThat(definition.fields())
                    .extracting(
                            TargetFieldDefinition::fieldKey,
                            TargetFieldDefinition::columnName,
                            TargetFieldDefinition::dataType,
                            TargetFieldDefinition::required
                    )
                    .containsExactlyInAnyOrderElementsOf(
                            Arrays.stream(entityType.getDeclaredFields())
                                    .filter(field -> field.isAnnotationPresent(Column.class))
                                    .map(field -> {
                                        Column column = field.getAnnotation(Column.class);
                                        DataType dataType = field.getType().equals(BigDecimal.class)
                                                ? DataType.NUMERIC
                                                : field.getType().equals(LocalDate.class)
                                                        ? DataType.DATE
                                                        : DataType.TEXT;
                                        return tuple(field.getName(), column.name(), dataType, !column.nullable());
                                    })
                                    .toList()
                    );
        });
    }

    @Test
    void everyWarehouseFieldRecognizesItsBusinessKeyAndPhysicalColumnName() {
        for (DataSetType type : new DataSetType[]{
                DataSetType.SALES,
                DataSetType.ACCOUNT_RECEIVABLE,
                DataSetType.PURCHASE,
                DataSetType.ACCOUNT_PAYABLE,
                DataSetType.PRODUCTION,
                DataSetType.BOM,
                DataSetType.MATERIAL_CONSUMPTION
        }) {
            for (TargetFieldDefinition field : catalog.getRequired(type).fields()) {
                assertThat(field.aliases()).contains(field.fieldKey(), field.columnName());
            }
        }
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
