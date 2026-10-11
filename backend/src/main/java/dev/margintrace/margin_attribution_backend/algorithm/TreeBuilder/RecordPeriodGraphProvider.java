package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;

/** Reuses the existing handler chain with original records; performs no warehouse queries. */
public final class RecordPeriodGraphProvider implements PeriodGraphProvider {
    private final PeriodRecords records;
    public RecordPeriodGraphProvider(PeriodRecords records) { this.records = records; }

    /** Build a period graph and retain missing-cost/coverage evidence separately from the CSR. */
    public Snapshot load(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) throw new IllegalArgumentException("Ordered period dates are required");
        for (var row : records.issuesBetween(start, end))
            if (row.materialId() != null && row.quantity() == null) throw new IllegalArgumentException("MISSING_ISSUE_QUANTITY:" + row.lineId());
        var chain = new AttributionWorkflow(new ReadDataHandler(records::salesBetween,
                new EdgeBuildHandler((product, from, to) -> records.issuesBetween(from, to).stream()
                        .filter(r -> product.equals(r.productId())).toList(),
                        (from, to) -> records.salesBetween(from, to).stream().anyMatch(s -> s.cost() == null)
                                ? null : records.salesBetween(from, to).stream().map(PeriodRecords.Sale::cost)
                                .reduce(BigDecimal.ZERO, BigDecimal::add), new CsrGraphHandler())));
        var diagnostics = new ArrayList<String>();
        if (!records.completeCoverage()) diagnostics.add("INCOMPLETE_GRAPH_COVERAGE");
        records.salesBetween(start, end).stream().filter(s -> s.cost() == null)
                .forEach(s -> diagnostics.add("MISSING_SALES_COST:" + s.sourceId()));
        records.issuesBetween(start, end).forEach(r -> {
            if (r.amount() == null) diagnostics.add("MISSING_ISSUE_COST:" + r.lineId());
            if (r.materialId() == null) diagnostics.add("MISSING_MATERIAL_ID:" + r.lineId());
            if (r.materialId() != null && r.materialId().equals(r.productId())) diagnostics.add("SELF_CONSUMPTION:" + r.lineId());
        });
        var graph = chain.trace(start, end);
        var positions = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < graph.nodes().length; i++) positions.put(graph.nodes()[i].inventoryId(), i);
        var sources = new java.util.LinkedHashMap<String, SourcePosition>();
        records.issuesBetween(start, end).forEach(r -> sources.put(r.lineId(), new SourcePosition(r.lineId(), r.productionId(),
                positions.get(r.materialId()), positions.get(r.productId()))));
        records.salesBetween(start, end).forEach(s -> sources.put("sale:" + s.sourceId(),
                new SourcePosition(s.sourceId(), null, positions.get(s.productId()), positions.get("__COGS__"))));
        return new Snapshot(graph, records, start, end, java.util.List.copyOf(diagnostics), java.util.Map.copyOf(sources));
    }
}
