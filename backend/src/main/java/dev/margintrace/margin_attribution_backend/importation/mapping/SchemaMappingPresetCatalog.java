package dev.margintrace.margin_attribution_backend.importation.mapping;

import dev.margintrace.margin_attribution_backend.importation.mapping.model.DataSetDefinition;
import dev.margintrace.margin_attribution_backend.importation.mapping.model.TargetFieldDefinition;
import dev.margintrace.margin_attribution_backend.importation.model.DataSetType;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

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
        register(presets, salesDetails());
        register(presets, purchases());
        register(presets, materialMaster());
        register(presets, inventoryMovement());
        register(presets, billOfMaterial());
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

    public DataSetDefinition getRequiredByTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank");
        }

        String normalizedTableName = normalizeTableName(tableName);
        return definitions.values().stream()
                .filter(definition -> definition.aliases().stream()
                        .map(SchemaMappingPresetCatalog::normalizeTableName)
                        .anyMatch(normalizedTableName::equals))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("No schema-mapping preset is defined for table: " + tableName));
    }

    private static String normalizeTableName(String tableName) {
        StringBuilder normalized = new StringBuilder();
        tableName
                .trim()
                .toLowerCase()
                .codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private static void register(Map<DataSetType, DataSetDefinition> definitions, DataSetDefinition definition) {
        if (definitions.putIfAbsent(definition.type(), definition) != null) {
            throw new IllegalStateException("Duplicate schema-mapping preset: " + definition.type());
        }
    }

    private static DataSetDefinition salesDetails() {
        return dataSet(
                DataSetType.SALES_DETAIL,
                "sales_details",
                Set.of("sales_detail", "sales details", "销售明细", "销售收入成本明细"),
                requiredDate("date", "date", "cost date", "posting date", "销售成本日期", "过账日期", "日期"),
                requiredText(
                        "movementNo",
                        "movement_no",
                        "movement no",
                        "cost movement no",
                        "inventory movement no",
                        "成本移动号",
                        "库存移动号",
                        "出入库单号"),
                requiredText(
                        "productNo",
                        "product_no",
                        "product no",
                        "product number",
                        "item no",
                        "item code",
                        "sku",
                        "产品编号",
                        "产品编码",
                        "商品编号",
                        "商品编码"),
                requiredNumeric(
                        "productQuantity",
                        "product_num",
                        "productNum",
                        "product quantity",
                        "quantity",
                        "qty",
                        "costed quantity",
                        "产品数量",
                        "商品数量",
                        "销售数量"),
                requiredNumeric(
                        "totalRevenue",
                        "total_revenue",
                        "totalRevenue",
                        "total revenue",
                        "sales revenue",
                        "revenue",
                        "销售收入",
                        "销售总额",
                        "收入金额"),
                requiredNumeric(
                        "totalCost",
                        "total_cost",
                        "totalCost",
                        "total cost",
                        "cost amount",
                        "cost of goods sold",
                        "销售成本",
                        "主营业务成本",
                        "总成本",
                        "成本金额"));
    }

    private static DataSetDefinition purchases() {
        return dataSet(
                DataSetType.PURCHASE,
                "purchases",
                Set.of("采购", "采购明细", "采购订单"),
                requiredDate("date", "date", "purchase date", "order date", "采购日期", "订单日期", "日期"),
                requiredText(
                        "purchaseOrderNo",
                        "purchase_order_no",
                        "purchase order no",
                        "po no",
                        "order no",
                        "purchase order number",
                        "采购单号",
                        "采购订单号",
                        "采购订单编号",
                        "订单号"),
                requiredText(
                        "materialNo",
                        "product_no",
                        "productNo",
                        "material no",
                        "material number",
                        "material code",
                        "product no",
                        "item no",
                        "item code",
                        "sku",
                        "物料编号",
                        "物料编码",
                        "材料编号",
                        "产品编号"),
                requiredNumeric(
                        "materialQuantity",
                        "product_num",
                        "productNum",
                        "material quantity",
                        "product quantity",
                        "purchase quantity",
                        "quantity",
                        "qty",
                        "物料数量",
                        "材料数量",
                        "采购数量"),
                requiredNumeric(
                        "lineTotalCost",
                        "product_total_price",
                        "productTotalPrice",
                        "line total cost",
                        "total cost",
                        "total price",
                        "purchase amount",
                        "amount",
                        "行总成本",
                        "采购总成本",
                        "采购总价",
                        "采购金额",
                        "总价",
                        "金额"));
    }

    private static DataSetDefinition materialMaster() {
        return dataSet(
                DataSetType.MATERIAL_MASTER,
                "material_master",
                Set.of("materials", "material", "物料", "物料明细", "物料主数据", "物料档案"),
                requiredText(
                        "materialNo",
                        "material_no",
                        "material no",
                        "material number",
                        "material code",
                        "item no",
                        "item code",
                        "sku",
                        "物料编号",
                        "物料编码",
                        "材料编号"),
                text("materialName", "material_name", "material name", "item name", "product name", "物料名称", "材料名称"),
                text("materialType", "material_type", "material type", "item type", "物料类型", "物料类别"),
                text("baseUom", "base_uom", "base uom", "base unit", "base unit of measure", "基本计量单位", "基本单位"),
                text("specification", "specification", "model", "specification model", "规格", "规格型号"),
                text("materialGroup", "material_group", "material group", "item group", "物料组", "物料分组"),
                text("status", "status", "material status", "状态", "物料状态"),
                text("supplier", "supplier", "supplier name", "vendor", "vendor name"),
                text("sourceKind", "source_kind", "source kind", "sourcing type"),
                numeric("unitCost", "unit_cost", "unit cost", "unit price"));
    }

    private static DataSetDefinition inventoryMovement() {
        return dataSet(
                DataSetType.INVENTORY_MOVEMENT,
                "inventory_usage",
                Set.of("inventory_movement", "库存移动", "库存流水", "出入库明细", "material_consumption", "物料消耗", "材料消耗", "生产领料"),
                requiredDate("date", "date", "movement date", "inventory date", "移动日期", "出入库日期", "日期"),
                requiredText(
                        "movementNo",
                        "movement_no",
                        "movement no",
                        "inventory movement no",
                        "material_consumption_no",
                        "materialConsumptionNo",
                        "material consumption no",
                        "material issue no",
                        "issue no",
                        "picking no",
                        "库存移动号",
                        "库存流水号",
                        "出入库单号",
                        "物料消耗单号",
                        "材料消耗单号",
                        "领料单号",
                        "出库单号"),
                requiredText(
                        "productNo",
                        "product_no",
                        "product no",
                        "product number",
                        "item no",
                        "sku",
                        "finished product no",
                        "finished goods no",
                        "产品编号",
                        "产品编码",
                        "商品编号",
                        "成品编号",
                        "成品编码"),
                numeric(
                        "productNum",
                        "product_num",
                        "product quantity",
                        "quantity",
                        "qty",
                        "movement quantity",
                        "产品数量",
                        "移动数量",
                        "出入库数量"),
                numeric("productTotalCost", "product_total_cost", "total cost", "product total cost", "产品总成本", "总成本"),
                requiredText(
                        "orderNo",
                        "order_no",
                        "order no",
                        "business order no",
                        "source order no",
                        "work order no",
                        "manufacturing order no",
                        "mo no",
                        "订单号",
                        "业务单号",
                        "来源单号",
                        "生产单号",
                        "生产订单号",
                        "生产订单编号",
                        "工单号"),
                text(
                        "materialNo",
                        "material_no",
                        "material no",
                        "material number",
                        "material code",
                        "component no",
                        "component code",
                        "物料编号",
                        "物料编码",
                        "材料编号",
                        "组件编号"),
                numeric(
                        "materialNum",
                        "material_num",
                        "material quantity",
                        "issued quantity",
                        "issue quantity",
                        "consumption quantity",
                        "发料数量",
                        "领料数量",
                        "物料数量",
                        "材料数量",
                        "消耗数量"),
                numeric(
                        "materialTotalCost",
                        "material_total_cost",
                        "material total cost",
                        "issued total cost",
                        "issue total cost",
                        "consumption cost",
                        "发料总成本",
                        "领料总成本",
                        "物料总成本",
                        "材料总成本",
                        "消耗成本"));
    }

    private static DataSetDefinition billOfMaterial() {
        return dataSet(
                DataSetType.BOM,
                "bill_of_material",
                Set.of("bom", "物料清单", "产品配方"),
                requiredText(
                        "bomNo",
                        "bom_no",
                        "bom no",
                        "bom number",
                        "bill of material no",
                        "bill of materials no",
                        "recipe no",
                        "bom编号",
                        "BOM编号",
                        "物料清单编号",
                        "配方编号"),
                requiredText(
                        "productNo",
                        "product_no",
                        "product no",
                        "product number",
                        "finished product no",
                        "finished goods no",
                        "parent item no",
                        "产品编号",
                        "产品编码",
                        "成品编号",
                        "成品编码",
                        "父项编号"),
                requiredText(
                        "materialNo",
                        "material_no",
                        "material no",
                        "material number",
                        "material code",
                        "component no",
                        "component code",
                        "ingredient no",
                        "child item no",
                        "物料编号",
                        "物料编码",
                        "材料编号",
                        "组件编号",
                        "子项编号"),
                requiredNumeric(
                        "materialUsage",
                        "material_usage",
                        "material usage",
                        "component usage",
                        "usage",
                        "quantity per",
                        "quantity per unit",
                        "usage quantity",
                        "物料用量",
                        "材料用量",
                        "组件用量",
                        "单位用量",
                        "单耗"));
    }

    private static DataSetDefinition dataSet(
            DataSetType type, String tableName, Set<String> aliases, TargetFieldDefinition... fields) {
        return new DataSetDefinition(type, tableName, aliases, List.of(fields));
    }

    private static TargetFieldDefinition requiredText(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.TEXT, true, aliases);
    }

    private static TargetFieldDefinition text(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.TEXT, false, aliases);
    }

    private static TargetFieldDefinition numeric(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.NUMERIC, false, aliases);
    }

    private static TargetFieldDefinition requiredNumeric(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.NUMERIC, true, aliases);
    }

    private static TargetFieldDefinition requiredDate(String fieldKey, String columnName, String... aliases) {
        return field(fieldKey, columnName, DataType.DATE, true, aliases);
    }

    private static TargetFieldDefinition field(
            String fieldKey, String columnName, DataType dataType, boolean required, String... aliases) {
        return new TargetFieldDefinition(fieldKey, columnName, dataType, required, Set.of(aliases));
    }
}
