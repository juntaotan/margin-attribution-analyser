package dev.margintrace.margin_attribution_backend.importation.mapping;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.SalesDetail;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.model.MaterialMaster;
import dev.margintrace.margin_attribution_backend.warehouse.model.PurchaseOrderLine;
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
    void warehousePresetsMatchEveryMappedEntityFieldAndColumn() {
        Map<DataSetType, Class<?>> warehouseEntities = Map.of(
                DataSetType.MATERIAL_MASTER, MaterialMaster.class,
                DataSetType.SALES_DETAIL, SalesDetail.class,
                DataSetType.PURCHASE, PurchaseOrderLine.class,
                DataSetType.INVENTORY_MOVEMENT, InventoryUsage.class,
                DataSetType.BOM, BillOfMaterial.class
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
                DataSetType.MATERIAL_MASTER,
                DataSetType.SALES_DETAIL,
                DataSetType.PURCHASE,
                DataSetType.BOM,
                DataSetType.INVENTORY_MOVEMENT
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
