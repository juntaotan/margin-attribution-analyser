package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportQueryServiceTests {
    private ReportQueryService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:report-query;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS sales_details");
        jdbcTemplate.execute("""
                CREATE TABLE sales_details (
                    id BIGINT PRIMARY KEY,
                    date DATE,
                    movement_no VARCHAR(100),
                    product_no VARCHAR(100),
                    product_num NUMERIC(18, 6),
                    total_revenue NUMERIC(18, 6),
                    total_cost NUMERIC(18, 6)
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO sales_details
                    (id, date, movement_no, product_no, product_num, total_revenue, total_cost)
                VALUES (1, '2026-01-15', 'M-1', 'P-1', 2, 40, 25)
                """);
        service = new ReportQueryService(jdbcTemplate);
    }

    @Test
    void executesGeneratedAggregateAgainstWarehouseData() {
        ReportQueryService.QueryResult result = service.execute(blueprint(
                "SUM(total_revenue - total_cost)",
                List.of("date BETWEEN '2026-01-01' AND '2026-01-31'")));

        assertThat(result.columns()).containsExactly("RESULT");
        assertThat(result.rows()).singleElement().satisfies(row ->
                assertThat(row.get("RESULT")).hasToString("15.000000"));
        assertThat(result.sql()).contains("FROM sales_details");
    }

    @Test
    void rejectsUnknownFieldsBeforeExecutingQuery() {
        assertThatThrownBy(() -> service.execute(blueprint("SUM(secret_value)", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported field or function");
    }

    @Test
    void rejectsStatementInjectionBeforeExecutingQuery() {
        assertThatThrownBy(() -> service.execute(blueprint(
                "SUM(total_cost); DROP TABLE sales_details", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe formula");
    }

    private ReportBlueprint blueprint(String formula, List<String> filters) {
        return new ReportBlueprint(
                "sales_details", "Sales details", filters, List.of(), formula,
                "Cost total", "currency", "Uses cost data");
    }
}
