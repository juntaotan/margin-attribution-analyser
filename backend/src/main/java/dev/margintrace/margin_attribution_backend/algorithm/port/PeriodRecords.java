package dev.margintrace.margin_attribution_backend.algorithm.port;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.Issue;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Original, immutable run records. Dates limit discovery, never complete-instance retrieval. */
public record PeriodRecords(List<Sale> sales, List<Issue> issues, boolean completeCoverage) {
    public PeriodRecords {
        sales = List.copyOf(sales); issues = List.copyOf(issues);
        if (sales.stream().anyMatch(s -> s.date() == null) || issues.stream().anyMatch(i -> i.date() == null))
            throw new IllegalArgumentException("Original records require identified dates");
    }
    public record Sale(String sourceId, LocalDate date, String productId, BigDecimal quantity, BigDecimal cost) {}
    public List<Sale> salesBetween(LocalDate start, LocalDate end) {
        return sales.stream().filter(s -> !s.date().isBefore(start) && !s.date().isAfter(end)).toList();
    }
    public List<Issue> issuesBetween(LocalDate start, LocalDate end) {
        return issues.stream().filter(s -> !s.date().isBefore(start) && !s.date().isAfter(end)).toList();
    }
}
