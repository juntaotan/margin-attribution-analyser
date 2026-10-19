package dev.margintrace.margin_attribution_backend.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Calculates the standard management-commentary values (periods, revenue, revenue change and gross margin) from
 * {@code sales_details}, for instantiating the master template.
 *
 * <p>Totals are summed as {@link java.math.BigDecimal}. If the totals for a period cannot be queried, for example
 * because a date is invalid, the failure is logged and that period's totals are treated as zero. The display
 * strings are formatted from {@code double} values.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportPlaceholderResolutionService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param actualStartDate first day of the current period, as an ISO date
     * @param actualEndDate last day of the current period, inclusive
     * @param comparableStartDate first day of the comparison period
     * @param comparableEndDate last day of the comparison period, inclusive
     * @return the display values, their raw numbers, and replacements keyed by every supported placeholder name.
     *     Percentages are rounded half-up to one decimal place and are zero when the denominator is zero.
     */
    public PlaceholderValues resolve(
            String actualStartDate, String actualEndDate, String comparableStartDate, String comparableEndDate) {
        String currentPeriod = formatPeriod(actualStartDate, actualEndDate);
        String comparisonPeriod = formatPeriod(comparableStartDate, comparableEndDate);
        PeriodTotals actual = queryTotals(actualStartDate, actualEndDate);
        PeriodTotals comparable = queryTotals(comparableStartDate, comparableEndDate);
        BigDecimal margin = actual.revenue().subtract(actual.cost());
        BigDecimal marginPercent = percentage(margin, actual.revenue());
        BigDecimal revenueChangePercent =
                percentage(actual.revenue().subtract(comparable.revenue()), comparable.revenue());

        String revenue = formatCurrency(actual.revenue());
        String revenueChange = formatPercentage(revenueChangePercent);
        String grossMargin = formatCurrency(margin);
        String grossMarginRate = String.format(Locale.ROOT, "%.1f%%", marginPercent.doubleValue());
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("Current Period", currentPeriod);
        replacements.put("Comparison Period", comparisonPeriod);
        replacements.put("current_period", currentPeriod);
        replacements.put("comparison_period", comparisonPeriod);
        replacements.put("Revenue", revenue);
        replacements.put("Revenue Change %", revenueChange);
        replacements.put("Gross Margin", grossMargin);
        replacements.put("Gross Margin %", grossMarginRate);
        replacements.put("Revenue Change Percent", revenueChange);
        replacements.put("Gross Margin Percent", grossMarginRate);
        replacements.put("revenue", revenue);
        replacements.put("revenue_change_percent", revenueChange);
        replacements.put("gross_margin", grossMargin);
        replacements.put("gross_margin_percent", grossMarginRate);
        replacements.put("Cost of Goods Sold", formatCurrency(actual.cost()));
        return new PlaceholderValues(
                currentPeriod,
                comparisonPeriod,
                revenue,
                actual.revenue().doubleValue(),
                revenueChange,
                revenueChangePercent.doubleValue(),
                grossMargin,
                margin.doubleValue(),
                grossMarginRate,
                marginPercent.doubleValue(),
                false,
                replacements);
    }

    private PeriodTotals queryTotals(String startDate, String endDate) {
        String sql = "SELECT COALESCE(SUM(total_revenue), 0), COALESCE(SUM(total_cost), 0) "
                + "FROM sales_details WHERE date >= ? AND date <= ?";
        try {
            Date start = Date.valueOf(LocalDate.parse(startDate.trim()));
            Date end = Date.valueOf(LocalDate.parse(endDate.trim()));
            return jdbcTemplate.queryForObject(
                    sql,
                    (result, row) -> new PeriodTotals(result.getBigDecimal(1), result.getBigDecimal(2)),
                    start,
                    end);
        } catch (Exception exception) {
            log.warn(
                    "Failed to query sales details for range [{} to {}]: {}",
                    startDate,
                    endDate,
                    exception.getMessage());
            return new PeriodTotals(BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 1, RoundingMode.HALF_UP);
    }

    private String formatPercentage(BigDecimal percentage) {
        return String.format(Locale.ROOT, "%+.1f%%", percentage.doubleValue()).replace("+0.0%", "0.0%");
    }

    private record PeriodTotals(BigDecimal revenue, BigDecimal cost) {}

    private String formatPeriod(String start, String end) {
        if (start == null || start.isBlank()) return end != null ? end : "";
        if (end == null || end.isBlank()) return start;
        return start.trim() + " to " + end.trim();
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) return "$0.00";
        return String.format(Locale.US, "$%,.2f", amount.doubleValue());
    }

    /**
     * Formatted placeholder values, their raw numbers, and the replacements to write into the template.
     */
    public record PlaceholderValues(
            String currentPeriod,
            String comparisonPeriod,
            String revenue,
            double revenueRaw,
            String revenueChangePercent,
            double revenueChangePercentRaw,
            String grossMargin,
            double grossMarginRaw,
            String grossMarginPercent,
            double grossMarginPercentRaw,
            boolean aiAssisted,
            Map<String, String> replacements) {}
}
