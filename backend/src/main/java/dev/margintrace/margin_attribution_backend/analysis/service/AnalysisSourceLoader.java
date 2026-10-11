package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.port.PeriodRecords;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.warehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Comparator;

/** Freeze source records in one read-only snapshot; inference and FGW run after this transaction ends. */
@Service
public class AnalysisSourceLoader {
    private final SalesDetailRepository sales;
    private final InventoryUsageRepository issues;
    public AnalysisSourceLoader(SalesDetailRepository sales, InventoryUsageRepository issues) { this.sales = sales; this.issues = issues; }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PeriodRecords load(boolean completeCoverage) {
        return new PeriodRecords(sales.findAll().stream().sorted(Comparator.comparing(s -> s.getDate().toString() + ":" + s.getId()))
                .map(s -> new PeriodRecords.Sale(s.getId() + "@" + s.getDate(), s.getDate(), s.getProductNo(), s.getProductQuantity(), s.getTotalCost())).toList(),
                issues.findAll().stream().sorted(Comparator.comparing(r -> r.getDate().toString() + ":" + r.getId()))
                .map(r -> new ProductionTree.Issue(r.getId() + "@" + r.getDate(), r.getDate(), r.getMovementNo(), r.getOrderNo(), r.getProductNo(),
                        r.getMaterialNo(), r.getMaterialNum(), r.getMaterialTotalCost(), r.getProductNum(), r.getProductTotalCost())).toList(), completeCoverage);
    }
}
