package dev.margintrace.margin_attribution_backend.importation.mapping;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only catalog of the system's built-in schema-mapping presets.
 *
 * <p>The catalog describes canonical tables and their business fields. It does not perform fuzzy matching and it does
 * not contain per-import user decisions. A later mapping service can use these definitions to generate a mapping draft.
 * </p>
 */
@Component
public class SchemaMappingPresetCatalog {
    private final Map<DataSetType, DataSetDefinition> definitions;

    public SchemaMappingPresetCatalog() {
        EnumMap<DataSetType, DataSetDefinition> presets = new EnumMap<>(DataSetType.class);
        register(presets, sales());
        register(presets, accountReceivables());
        register(presets, purchases());
        register(presets, accountPayables());
        register(presets, inventoryMovement());
        register(presets, production());
        register(presets, billOfMaterial());
        register(presets, materialConsumption());
        definitions = Map.copyOf(presets);
    }

    public Collection<DataSetDefinition> getAll() {
        return definitions.values();
    }

    public DataSetDefinition getRequired(DataSetType type) {
        DataSetDefinition definition = definitions.get(type);
        if (definition == null) {
            throw new IllegalArgumentException("No schema-mapping preset is defined for data set: " + type);
        }
        return definition;
    }

    private static void register(
            Map<DataSetType, DataSetDefinition> definitions,
            DataSetDefinition definition
    ) {
        if (definitions.putIfAbsent(definition.type(), definition) != null) {
            throw new IllegalStateException("Duplicate schema-mapping preset: " + definition.type());
        }
    }

    private static DataSetDefinition sales() {
        return dataSet(DataSetType.SALES, "sales", Set.of("销售", "销售明细", "销售订单"),
                requiredText("saleOrderNo", "sale_order_no", "sale order no", "sales order no", "order no",
                        "销售单号", "销售订单号", "订单号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku",
                        "产品编号", "商品编号", "物料编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty",
                        "产品数量", "商品数量", "销售数量"),
                numeric("productUnitPrice", "product_unit_price", "unit price", "sales unit price",
                        "销售单价", "产品单价", "商品单价"),
                numeric("productTotalPrice", "product_total_price", "total price", "sales amount", "amount",
                        "销售总价", "销售金额", "总价", "金额"),
                text("movementNo", "movement_no", "movement no", "inventory movement no",
                        "库存移动号", "库存流水号", "出库单号"));
    }

    private static DataSetDefinition accountReceivables() {
        return dataSet(DataSetType.ACCOUNT_RECEIVABLE, "account_receivables",
                Set.of("应收", "应收账款", "应收明细"),
                requiredText("accountReceivableNo", "account_receivable_no", "account receivable no", "ar no",
                        "应收单号", "应收账款编号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku",
                        "产品编号", "商品编号", "物料编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty",
                        "产品数量", "商品数量"),
                numeric("productUnitPrice", "product_unit_price", "unit price", "sales unit price",
                        "产品单价", "商品单价", "销售单价"),
                numeric("productTotalPrice", "product_total_price", "total price", "receivable amount", "amount",
                        "应收金额", "销售金额", "总价", "金额"),
                requiredText("saleOrderNo", "sale_order_no", "sale order no", "sales order no",
                        "销售单号", "销售订单号"));
    }

    private static DataSetDefinition purchases() {
        return dataSet(DataSetType.PURCHASE, "purchases", Set.of("采购", "采购明细", "采购订单"),
                requiredText("purchaseOrderNo", "purchase_order_no", "purchase order no", "po no", "order no",
                        "采购单号", "采购订单号", "订单号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku", "material no",
                        "产品编号", "商品编号", "物料编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty", "purchase quantity",
                        "产品数量", "采购数量"),
                numeric("productUnitPrice", "product_unit_price", "unit price", "purchase unit price",
                        "采购单价", "产品单价"),
                numeric("productTotalPrice", "product_total_price", "total price", "purchase amount", "amount",
                        "采购总价", "采购金额", "总价", "金额"),
                text("movementNo", "movement_no", "movement no", "inventory movement no",
                        "库存移动号", "库存流水号", "入库单号"));
    }

    private static DataSetDefinition accountPayables() {
        return dataSet(DataSetType.ACCOUNT_PAYABLE, "account_payables", Set.of("应付", "应付账款", "应付明细"),
                requiredText("accountPayableNo", "account_payable_no", "account payable no", "ap no",
                        "应付单号", "应付账款编号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku", "material no",
                        "产品编号", "商品编号", "物料编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty",
                        "产品数量", "采购数量"),
                numeric("productUnitPrice", "product_unit_price", "unit price", "purchase unit price",
                        "采购单价", "产品单价"),
                numeric("productTotalPrice", "product_total_price", "total price", "payable amount", "amount",
                        "应付金额", "采购金额", "总价", "金额"),
                requiredText("purchaseOrderNo", "purchase_order_no", "purchase order no", "po no",
                        "采购单号", "采购订单号"));
    }

    private static DataSetDefinition inventoryMovement() {
        return dataSet(DataSetType.INVENTORY_MOVEMENT, "inventory_movement",
                Set.of("库存移动", "库存流水", "出入库明细"),
                requiredText("movementNo", "movement_no", "movement no", "inventory movement no",
                        "库存移动号", "库存流水号", "出入库单号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku", "material no",
                        "产品编号", "商品编号", "物料编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty", "movement quantity",
                        "产品数量", "移动数量", "出入库数量"),
                numeric("productUnitCost", "product_unit_cost", "unit cost", "product unit cost",
                        "产品单位成本", "单位成本"),
                numeric("productTotalCost", "product_total_cost", "total cost", "product total cost",
                        "产品总成本", "总成本"),
                requiredText("orderNo", "order_no", "order no", "business order no", "source order no",
                        "订单号", "业务单号", "来源单号"));
    }

    private static DataSetDefinition production() {
        return dataSet(DataSetType.PRODUCTION, "production", Set.of("生产", "生产明细", "生产订单"),
                requiredText("productionOrderNo", "production_order_no", "production order no", "work order no",
                        "生产单号", "生产订单号", "工单号"),
                requiredText("productNo", "product_no", "product no", "item no", "sku",
                        "产品编号", "成品编号"),
                numeric("productNum", "product_num", "product quantity", "quantity", "qty", "production quantity",
                        "产品数量", "生产数量"),
                numeric("productUnitCost", "product_unit_cost", "unit cost", "product unit cost",
                        "产品单位成本", "单位成本"),
                numeric("productTotalCost", "product_total_cost", "total cost", "product total cost",
                        "产品总成本", "总成本"),
                requiredText("productDepartment", "product_department", "production department", "department",
                        "生产部门", "部门"));
    }

    private static DataSetDefinition billOfMaterial() {
        return dataSet(DataSetType.BOM, "bill_of_material", Set.of("bom", "物料清单", "产品配方"),
                requiredText("bomNo", "bom_no", "bom no", "bill of material no",
                        "bom编号", "物料清单编号", "配方编号"),
                requiredText("productNo", "product_no", "product no", "finished product no",
                        "产品编号", "成品编号"),
                requiredText("materialNo", "material_no", "material no", "component no", "ingredient no",
                        "物料编号", "材料编号", "组件编号"),
                requiredNumeric("materialUsage", "material_usage", "material usage", "usage", "quantity per",
                        "物料用量", "材料用量", "单位用量"));
    }

    private static DataSetDefinition materialConsumption() {
        return dataSet(DataSetType.MATERIAL_CONSUMPTION, "material_consumption",
                Set.of("物料消耗", "材料消耗", "生产领料"),
                requiredText("productionOrderNo", "production_order_no", "production order no", "work order no",
                        "生产单号", "生产订单号", "工单号"),
                requiredText("productNo", "product_no", "product no", "finished product no",
                        "产品编号", "成品编号"),
                requiredText("materialNo", "material_no", "material no", "component no",
                        "物料编号", "材料编号"),
                numeric("materialNum", "material_num", "material quantity", "consumption quantity", "quantity",
                        "物料数量", "材料数量", "消耗数量"),
                numeric("materialUnitCost", "material_unit_cost", "material unit cost", "unit cost",
                        "物料单位成本", "材料单位成本", "单位成本"),
                numeric("materialTotalCost", "material_total_cost", "material total cost", "total cost",
                        "物料总成本", "材料总成本", "总成本"),
                requiredText("movementNo", "movement_no", "movement no", "inventory movement no",
                        "库存移动号", "库存流水号", "领料单号"));
    }

    private static DataSetDefinition dataSet(
            DataSetType type,
            String tableName,
            Set<String> aliases,
            TargetFieldDefinition... fields
    ) {
        return new DataSetDefinition(type, tableName, aliases, List.of(fields));
    }

    private static TargetFieldDefinition requiredText(
            String fieldKey,
            String columnName,
            String... aliases
    ) {
        return field(fieldKey, columnName, DataType.TEXT, true, aliases);
    }

    private static TargetFieldDefinition text(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.TEXT, false, aliases);
    }

    private static TargetFieldDefinition numeric(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.NUMERIC, false, aliases);
    }

    private static TargetFieldDefinition requiredNumeric(
            String fieldKey,
            String columnName,
            String... aliases
    ) {
        return field(fieldKey, columnName, DataType.NUMERIC, true, aliases);
    }

    private static TargetFieldDefinition field(
            String fieldKey,
            String columnName,
            DataType dataType,
            boolean required,
            String... aliases
    ) {
        return new TargetFieldDefinition(fieldKey, columnName, dataType, required, Set.of(aliases));
    }
}
