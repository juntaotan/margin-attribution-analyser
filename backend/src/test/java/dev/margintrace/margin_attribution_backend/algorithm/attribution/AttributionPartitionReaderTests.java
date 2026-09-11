package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.entry;

class AttributionPartitionReaderTests {
    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private AttributionPartitionReader reader;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        reader = new AttributionPartitionReader(new NamedParameterJdbcTemplate(database));

        jdbcTemplate.execute("""
                CREATE TABLE production_order (
                    id BIGINT PRIMARY KEY,
                    production_order_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL,
                    product_num NUMERIC(18, 6) NOT NULL,
                    CONSTRAINT uk_test_production_product
                        UNIQUE (production_order_no, product_no)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE material_consumption (
                    id BIGINT PRIMARY KEY,
                    production_order_no VARCHAR(100) NOT NULL,
                    product_no VARCHAR(100) NOT NULL,
                    material_no VARCHAR(100) NOT NULL,
                    material_num NUMERIC(18, 6) NOT NULL,
                    material_total_cost NUMERIC(18, 6),
                    CONSTRAINT fk_test_material_consumption_production
                        FOREIGN KEY (production_order_no, product_no)
                        REFERENCES production_order (production_order_no, product_no)
                )
                """);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void returnsProductsWithinTheInclusiveIdPeriodInIdOrder() {
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                3L, "PRODUCTION-003", "PRODUCT-003", 30
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                1L, "PRODUCTION-001", "PRODUCT-001", 10
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                2L, "PRODUCTION-002", "PRODUCT-002", 20
        );

        assertThat(reader.getAllProductsInPeriod(1L, 2L))
                .containsExactly(
                        entry("PRODUCT-001", node("PRODUCT-001", 10)),
                        entry("PRODUCT-002", node("PRODUCT-002", 20))
                );
    }

    @Test
    void rejectsAnInvertedIdPeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> reader.getAllProductsInPeriod(2L, 1L))
                .withMessage("startId must not be greater than endId");
    }

    @Test
    void readsAllMaterialToProductEdgesForProductsInTheRequestedPeriod() {
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                1L, "PRODUCTION-001", "PRODUCT-001", 10
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                2L, "PRODUCTION-002", "PRODUCT-002", 20
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, production_order_no, product_no, product_num) VALUES (?, ?, ?, ?)",
                3L, "PRODUCTION-003", "PRODUCT-OUTSIDE-PERIOD", 30
        );

        insertMaterial(1L, "PRODUCTION-001", "PRODUCT-001", "MATERIAL-001", 4);
        insertMaterial(9L, "PRODUCTION-001", "PRODUCT-001", "MATERIAL-002", 6);
        insertMaterial(17L, "PRODUCTION-002", "PRODUCT-002", "MATERIAL-003", 8);
        insertMaterial(25L, "PRODUCTION-003", "PRODUCT-OUTSIDE-PERIOD", "MATERIAL-004", 9);
        insertMaterial(2L, "PRODUCTION-001", "PRODUCT-001", "MATERIAL-005", 5);
        insertMaterial(33L, "PRODUCTION-001", "PRODUCT-001", "MATERIAL-SHARED", 2);
        insertMaterial(41L, "PRODUCTION-002", "PRODUCT-002", "MATERIAL-SHARED", 2);

        assertThat(reader.readMaterialUsage(1L, 2L))
                .containsOnly(
                        entry(node("MATERIAL-001", 4), List.of(node("PRODUCT-001", 10))),
                        entry(node("MATERIAL-002", 6), List.of(node("PRODUCT-001", 10))),
                        entry(node("MATERIAL-003", 8), List.of(node("PRODUCT-002", 20))),
                        entry(node("MATERIAL-005", 5), List.of(node("PRODUCT-001", 10))),
                        entry(
                                node("MATERIAL-SHARED", 2),
                                List.of(node("PRODUCT-001", 10), node("PRODUCT-002", 20))
                        )
                );
    }

    @Test
    void returnsAnEmptyMapWhenThePeriodHasNoProducts() {
        assertThat(reader.readMaterialUsage(1L, 2L)).isEmpty();
    }

    @Test
    void readsRecordedMaterialCostWithoutInventingProductCost() {
        jdbcTemplate.update("INSERT INTO production_order VALUES (1, 'PO-1', 'P', 10)");
        jdbcTemplate.update("INSERT INTO material_consumption VALUES (1, 'PO-1', 'P', 'M', 4, 123.45)");

        assertThat(reader.readMaterialUsage(1, 1)).containsOnly(entry(
                new Node("M", new BigDecimal("4"), new BigDecimal("123.45")),
                List.of(node("P", 10))));
        assertThat(reader.getAllProductsInPeriod(1, 1).get("P").cost()).isNull();
    }

    @Test
    void readMaterialUsageRejectsAnInvertedIdPeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> reader.readMaterialUsage(2L, 1L))
                .withMessage("startId must not be greater than endId");
    }

    private void insertMaterial(
            long id,
            String productionOrderNo,
            String productNo,
            String materialNo,
            int materialQuantity
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO material_consumption
                            (id, production_order_no, product_no, material_no, material_num)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                id, productionOrderNo, productNo, materialNo, materialQuantity
        );
    }

    private Node node(String inventoryId, long quantity) {
        return new Node(inventoryId, BigDecimal.valueOf(quantity));
    }
}
