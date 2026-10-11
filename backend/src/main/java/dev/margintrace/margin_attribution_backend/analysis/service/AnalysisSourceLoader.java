package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.port.PeriodRecords;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.warehouse.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.Comparator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Freeze source records in one read-only snapshot; inference and FGW run after this transaction ends. */
@Service
public class AnalysisSourceLoader {
    private final SalesDetailRepository sales;
    private final InventoryUsageRepository issues;
    private final MaterialMasterRepository materials;
    public AnalysisSourceLoader(SalesDetailRepository sales, InventoryUsageRepository issues, MaterialMasterRepository materials) {
        this.sales = sales; this.issues = issues; this.materials = materials;
    }

    /** Immutable material attributes captured in the same transaction as sales and issues. */
    public record Material(String code, String name, String specification, String category,
                           String unit, String supplier, String sourceKind, BigDecimal unitCost) {}
    public record Snapshot(PeriodRecords records, Map<String, Material> materials) {
        public Snapshot { materials = Map.copyOf(materials); }
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PeriodRecords load(boolean completeCoverage) {
        return readRecords(completeCoverage);
    }

    /** Freeze all warehouse inputs before inference; no JPA entities escape this read-only snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot loadSnapshot(boolean completeCoverage) {
        var descriptions = materials.findAll().stream().map(m -> new Material(m.getMaterialNo(),
                m.getMaterialName(), m.getSpecification(),
                m.getMaterialGroup() == null || m.getMaterialGroup().isBlank() ? m.getMaterialType() : m.getMaterialGroup(),
                m.getBaseUom(), m.getSupplier(), m.getSourceKind(), m.getUnitCost()))
                .collect(Collectors.toMap(Material::code, Function.identity()));
        return new Snapshot(readRecords(completeCoverage), descriptions);
    }

    private PeriodRecords readRecords(boolean completeCoverage) {
        return new PeriodRecords(sales.findAll().stream().sorted(Comparator.comparing(s -> s.getDate().toString() + ":" + s.getId()))
                .map(s -> new PeriodRecords.Sale(s.getId() + "@" + s.getDate(), s.getDate(), s.getProductNo(), s.getProductQuantity(), s.getTotalCost())).toList(),
                issues.findAll().stream().sorted(Comparator.comparing(r -> r.getDate().toString() + ":" + r.getId()))
                .map(r -> new ProductionTree.Issue(r.getId() + "@" + r.getDate(), r.getDate(), r.getMovementNo(), r.getOrderNo(), r.getProductNo(),
                        r.getMaterialNo(), r.getMaterialNum(), r.getMaterialTotalCost(), r.getProductNum(), r.getProductTotalCost())).toList(), completeCoverage);
    }
}
