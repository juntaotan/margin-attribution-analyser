package dev.margintrace.margin_attribution_backend.importation.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.margintrace.margin_attribution_backend.importation.context.ImportContext;
import dev.margintrace.margin_attribution_backend.importation.mapping.SchemaMappingPresetCatalog;
import dev.margintrace.margin_attribution_backend.importation.model.DataType;
import dev.margintrace.margin_attribution_backend.importation.model.TableStructure;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class DataWarehouseWriterHandlerTests {
    private final java.util.List<java.sql.Connection> connections = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeConnections() throws Exception {
        for (var connection : connections) connection.close();
    }

    @Test
    void importsSalesRevenueCostAndProductTarget() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("CREATE SCHEMA raw");
        jdbcTemplate.execute(
                """
                CREATE TABLE sales_details (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    date DATE NOT NULL, movement_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL, product_num NUMERIC(18,6) NOT NULL,
                    total_revenue NUMERIC(18,6) NOT NULL, total_cost NUMERIC(18,6) NOT NULL
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE raw.sales_lines (
                    sale_date DATE, movement VARCHAR(100), item_no VARCHAR(100),
                    quantity NUMERIC(18,6), revenue NUMERIC(18,6), cost NUMERIC(18,6)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO raw.sales_lines VALUES (?, ?, ?, ?, ?, ?)",
                java.sql.Date.valueOf("2026-01-15"),
                "MOVE-1",
                "PROD-A",
                2,
                50,
                30);

        ImportContext context = new ImportContext();
        context.setMappingTableName("sales_details");
        context.setMappingResult(true);
        context.setTableStructure(new TableStructure("sales", 0, 0, 5, 1, 1.0));
        context.setColumnTypes(new LinkedHashMap<>(Map.of(
                "Cost Date",
                DataType.DATE,
                "Movement No",
                DataType.TEXT,
                "Item No",
                DataType.TEXT,
                "Quantity",
                DataType.NUMERIC,
                "Total Revenue",
                DataType.NUMERIC,
                "Total Cost",
                DataType.NUMERIC)));
        context.setRawTableName("raw.sales_lines");
        context.setRawColumnNames(Map.of(
                "Cost Date",
                "sale_date",
                "Movement No",
                "movement",
                "Item No",
                "item_no",
                "Quantity",
                "quantity",
                "Total Revenue",
                "revenue",
                "Total Cost",
                "cost"));

        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);
        new DataWarehouseWriterHandler(jdbcTemplate).doImport(context);

        assertThat(context.getWarehouseImportedRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT product_no, total_revenue, total_cost FROM sales_details"))
                .containsEntry("PRODUCT_NO", "PROD-A")
                .containsEntry("TOTAL_REVENUE", new BigDecimal("50.000000"))
                .containsEntry("TOTAL_COST", new BigDecimal("30.000000"));
    }

    @Test
    void importsMaterialWithOnlyItsNumber() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("CREATE SCHEMA raw");
        jdbcTemplate.execute(
                """
                CREATE TABLE material_master (
                    material_no VARCHAR(100) NOT NULL PRIMARY KEY,
                    material_name VARCHAR(200), material_type VARCHAR(30),
                    base_uom VARCHAR(16), specification VARCHAR(255),
                    material_group VARCHAR(100), status VARCHAR(20)
                )
                """);
        applyMaterialEmbeddingMigration(jdbcTemplate);
        jdbcTemplate.execute("CREATE TABLE raw.materials (material_no VARCHAR(100))");
        jdbcTemplate.update("INSERT INTO raw.materials (material_no) VALUES (?)", "MAT-1");

        ImportContext context = new ImportContext();
        context.setMappingTableName("material_master");
        context.setMappingResult(true);
        context.setTableStructure(new TableStructure("materials", 0, 0, 0, 1, 1.0));
        context.setColumnTypes(Map.of("Material No", DataType.TEXT));
        context.setRawTableName("raw.materials");
        context.setRawColumnNames(Map.of("Material No", "material_no"));

        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);
        new DataWarehouseWriterHandler(jdbcTemplate).doImport(context);

        assertThat(context.getWarehouseImportedRows()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM material_master"))
                .containsEntry("MATERIAL_NO", "MAT-1")
                .containsEntry("MATERIAL_NAME", null)
                .containsEntry("BASE_UOM", null)
                .containsEntry("SUPPLIER", null)
                .containsEntry("SOURCE_KIND", null)
                .containsEntry("UNIT_COST", null);
    }

    @Test
    void importsEmbeddingAttributesAndPreservesExistingMaterials() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("CREATE SCHEMA raw");
        jdbcTemplate.execute("CREATE TABLE material_master (material_no VARCHAR(100) PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO material_master VALUES (?)", "EXISTING");
        applyMaterialEmbeddingMigration(jdbcTemplate);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM material_master WHERE material_no = 'EXISTING'"))
                .containsEntry("SUPPLIER", null)
                .containsEntry("SOURCE_KIND", null)
                .containsEntry("UNIT_COST", null);
        jdbcTemplate.execute(
                """
                CREATE TABLE raw.materials (
                    material_no VARCHAR(100), vendor VARCHAR(200),
                    source_kind VARCHAR(16), unit_cost NUMERIC(18,6)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO raw.materials VALUES (?, ?, ?, ?)",
                "RAW",
                "Supplier A",
                "PURCHASED",
                new BigDecimal("12.345678"));
        jdbcTemplate.update(
                "INSERT INTO raw.materials VALUES (?, ?, ?, ?)", "PRODUCT", null, "INTERNAL", BigDecimal.ZERO);
        ImportContext context = new ImportContext();
        context.setMappingTableName("material_master");
        context.setMappingResult(true);
        context.setTableStructure(new TableStructure("materials", 0, 0, 3, 2, 1.0));
        context.setColumnTypes(Map.of(
                "Material No",
                DataType.TEXT,
                "Vendor",
                DataType.TEXT,
                "Source Kind",
                DataType.TEXT,
                "Unit Cost",
                DataType.NUMERIC));
        context.setRawTableName("raw.materials");
        context.setRawColumnNames(Map.of(
                "Material No",
                "material_no",
                "Vendor",
                "vendor",
                "Source Kind",
                "source_kind",
                "Unit Cost",
                "unit_cost"));
        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);
        new DataWarehouseWriterHandler(jdbcTemplate).doImport(context);
        assertThat(context.getWarehouseImportedRows()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM material_master WHERE material_no = 'RAW'"))
                .containsEntry("SUPPLIER", "Supplier A")
                .containsEntry("SOURCE_KIND", "PURCHASED")
                .containsEntry("UNIT_COST", new BigDecimal("12.345678"));
        assertThat(jdbcTemplate.queryForMap("SELECT * FROM material_master WHERE material_no = 'PRODUCT'"))
                .containsEntry("SUPPLIER", null)
                .containsEntry("SOURCE_KIND", "INTERNAL")
                .containsEntry("UNIT_COST", new BigDecimal("0.000000"));
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO material_master (material_no, source_kind) VALUES (?, ?)", "INVALID", "UNKNOWN"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO material_master (material_no, unit_cost) VALUES (?, ?)", "NEGATIVE", -1))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private static void applyMaterialEmbeddingMigration(JdbcTemplate jdbcTemplate) throws Exception {
        ScriptUtils.executeSqlScript(
                jdbcTemplate.getDataSource().getConnection(),
                new ClassPathResource("db/migration/V8__add_material_embedding_fields.sql"));
    }

    @Test
    void importsMaterialConsumptionDirectlyIntoInventoryUsage() throws Exception {
        JdbcTemplate jdbcTemplate = createJdbcTemplate();
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS raw");
        jdbcTemplate.execute(
                """
                CREATE TABLE inventory_usage (
                    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    date DATE NOT NULL,
                    movement_no VARCHAR(100) NOT NULL,
                    order_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL,
                    material_no VARCHAR(100),
                    material_num NUMERIC(18,6),
                    material_total_cost NUMERIC(18,6)
                )
                """);
        jdbcTemplate.execute(
                """
                CREATE TABLE raw.material_lines (
                    consumption_no VARCHAR(100), production_no VARCHAR(100), movement_date DATE,
                    product_no VARCHAR(100), material_no VARCHAR(100),
                    material_num NUMERIC(18,6), material_cost NUMERIC(18,6)
                )
                """);
        jdbcTemplate.update(
                "INSERT INTO raw.material_lines VALUES (?, ?, ?, ?, ?, ?, ?)",
                "MC-1",
                "PO-1",
                java.sql.Date.valueOf("2026-01-15"),
                "P",
                "M",
                4,
                123.45);

        ImportContext context = new ImportContext();
        context.setMappingTableName("material_consumption");
        context.setMappingResult(true);
        context.setTableStructure(new TableStructure("materials", 0, 0, 5, 1, 1.0));
        Map<String, DataType> columns = new LinkedHashMap<>();
        columns.put("Material Consumption No", DataType.TEXT);
        columns.put("Movement Date", DataType.DATE);
        columns.put("Work Order No", DataType.TEXT);
        columns.put("Product No", DataType.TEXT);
        columns.put("Material No", DataType.TEXT);
        columns.put("Issued Quantity", DataType.NUMERIC);
        columns.put("Issued Total Cost", DataType.NUMERIC);
        context.setColumnTypes(columns);
        context.setRawTableName("raw.material_lines");
        context.setRawColumnNames(Map.of(
                "Material Consumption No", "consumption_no",
                "Movement Date", "movement_date",
                "Work Order No", "production_no",
                "Product No", "product_no",
                "Material No", "material_no",
                "Issued Quantity", "material_num",
                "Issued Total Cost", "material_cost"));

        new SchemaMappingHandler(new SchemaMappingPresetCatalog()).doImport(context);
        new DataWarehouseWriterHandler(jdbcTemplate).doImport(context);

        assertThat(context.getWarehouseImportedRows()).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForMap(
                                """
                SELECT date, movement_no, order_no, product_no,
                       material_no, material_num, material_total_cost
                FROM inventory_usage
                """))
                .containsEntry("DATE", java.sql.Date.valueOf("2026-01-15"))
                .containsEntry("MOVEMENT_NO", "MC-1")
                .containsEntry("ORDER_NO", "PO-1")
                .containsEntry("PRODUCT_NO", "P")
                .containsEntry("MATERIAL_NO", "M")
                .containsEntry("MATERIAL_NUM", new BigDecimal("4.000000"))
                .containsEntry("MATERIAL_TOTAL_COST", new BigDecimal("123.450000"));
    }

    private JdbcTemplate createJdbcTemplate() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:data-warehouse-writer-" + java.util.UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        var connection = dataSource.getConnection();
        connections.add(connection);
        return new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
    }
}
