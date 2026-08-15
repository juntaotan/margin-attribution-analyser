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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

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
                    product_no VARCHAR(100) NOT NULL,
                    product_num NUMERIC(18, 6) NOT NULL
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
                "INSERT INTO production_order (id, product_no, product_num) VALUES (?, ?, ?)",
                3L, "PRODUCT-003", 30
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, product_no, product_num) VALUES (?, ?, ?)",
                1L, "PRODUCT-001", 10
        );
        jdbcTemplate.update(
                "INSERT INTO production_order (id, product_no, product_num) VALUES (?, ?, ?)",
                2L, "PRODUCT-002", 20
        );

        assertThat(reader.getAllProductsInPeriod(1L, 2L))
                .containsExactly(
                        new Node("PRODUCT-001", 10),
                        new Node("PRODUCT-002", 20)
                );
    }

    @Test
    void rejectsAnInvertedIdPeriod() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> reader.getAllProductsInPeriod(2L, 1L))
                .withMessage("startId must not be greater than endId");
    }
}
