package dev.margintrace.margin_attribution_backend.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportPlaceholderResolutionServiceTests {

    @Test
    void resolvesRevenueAndMarginFromSalesDetails() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:sales-placeholder-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute(
                """
                CREATE TABLE sales_details (
                    date DATE NOT NULL,
                    total_revenue NUMERIC(18, 6) NOT NULL,
                    total_cost NUMERIC(18, 6) NOT NULL
                )
                """);
        jdbc.update("INSERT INTO sales_details VALUES (DATE '2025-01-15', 100, 80)");
        jdbc.update("INSERT INTO sales_details VALUES (DATE '2026-01-15', 150, 90)");
        jdbc.update("INSERT INTO sales_details VALUES (DATE '2026-01-16', 50, 30)");

        var values = new ReportPlaceholderResolutionService(jdbc)
                .resolve("2026-01-01", "2026-01-31", "2025-01-01", "2025-01-31");

        assertThat(values.revenue()).isEqualTo("$200.00");
        assertThat(values.revenueChangePercent()).isEqualTo("+100.0%");
        assertThat(values.grossMargin()).isEqualTo("$80.00");
        assertThat(values.grossMarginPercent()).isEqualTo("40.0%");
        assertThat(values.replacements()).containsEntry("Cost of Goods Sold", "$120.00");
    }
}
