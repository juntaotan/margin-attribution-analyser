package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath;
import java.util.*;

/** Selects receiving sold products along actual branches, then restores full instances from retained records. */
public final class RecordProductionDataProvider implements ProductionDataProvider {
    /**
     * Follow recorded relationships to sold receiving products. Supplying instances are loaded as
     * lineage evidence, not independently selected roots. Comparable roots need no abnormal path.
     * Terminal queries deliberately retain out-of-scope candidates for the builder's exclusion gate.
     */
    public Selection load(PeriodGraphProvider.Snapshot actual, PeriodGraphProvider.Snapshot comparable,
                          List<PropagationPath> branches, ProductionTree.Evidence evidence) {
        Map<String, Set<String>> reached = new TreeMap<>();
        for (int i = 0; i < branches.size(); i++) for (int pos : branches.get(i).positions()) {
            String code = actual.graph().nodes()[pos].inventoryId();
            if (!"__COGS__".equals(code)) reached.computeIfAbsent(code, k -> new TreeSet<>()).add("actual:branch:" + i);
        }
        boolean changed;
        do {
            changed = false;
            for (var issue : actual.records().issuesBetween(actual.start(), actual.end())) {
                var refs = reached.get(issue.materialId());
                if (refs != null && issue.productId() != null)
                    changed |= reached.computeIfAbsent(issue.productId(), k -> new TreeSet<>()).addAll(refs);
            }
        } while (changed);
        Set<String> sold = new TreeSet<>();
        actual.records().salesBetween(actual.start(), actual.end()).forEach(s -> sold.add(s.productId()));
        reached.keySet().retainAll(sold);
        var selections = new TreeMap<String, Set<String>>();
        var periods = new HashMap<String, PeriodGraphProvider.Snapshot>();
        var identities = new ArrayList<Identity>();
        for (var period : List.of(actual, comparable)) {
            String role = period == actual ? "actual" : "comparable";
            for (var row : period.records().issuesBetween(period.start(), period.end())) {
                if (!reached.containsKey(row.productId())) continue;
                if (ProductionTreeBuilder.blank(row.productionId())) {
                    identities.add(new Identity(row.productionId(), Set.of("MISSING_PRODUCTION_ID"), List.of(role + ":" + row.lineId()))); continue;
                }
                var refs = selections.computeIfAbsent(row.productionId(), k -> new TreeSet<>());
                refs.addAll(reached.get(row.productId())); refs.add(role + ":issue:" + row.lineId());
                periods.putIfAbsent(row.productionId(), period);
            }
        }
        // Preserve conflicting duplicate identities for the builder, rather than normalising them away.
        var all = new ArrayList<>(actual.records().issues());
        if (actual.records() != comparable.records()) all.addAll(comparable.records().issues());
        var trees = new ArrayList<ProductionTree>();
        var builder = new ProductionTreeBuilder();
        for (var selected : selections.entrySet()) {
            var period = periods.get(selected.getKey());
            Map<String, String> roots = new TreeMap<>();
            // Scope roots to this selection; other periods can contain products absent from this CSR.
            all.stream().filter(r -> selected.getKey().equals(r.productionId()) && r.productId() != null)
                    .forEach(r -> roots.putIfAbsent(r.productionId(), r.productId()));
            var graph = period.graph();
            var nodes = Arrays.stream(graph.nodes()).map(n -> new ProductionTree.GraphNode(n.inventoryId(), n.inventoryId())).toList();
            var edgeIds = java.util.stream.IntStream.range(0, graph.successors().length)
                    .mapToObj(i -> (period == actual ? "actual" : "comparable") + ":edge:" + i).toList();
            var scope = new ProductionTree.ActualGraph(period == actual ? "actual" : "comparable", evidence.namespace(),
                    ProductionTree.Direction.MATERIAL_TO_PRODUCT, true, nodes, graph.offset().clone(), graph.successors().clone(),
                    edgeIds, roots, period.records().completeCoverage(), period.start(), period.end());
            try {
                trees.add(builder.build(selected.getKey(), evidence, scope,
                        id -> all.stream().filter(r -> id.equals(r.productionId())).toList(),
                        material -> all.stream().filter(r -> material.equals(r.materialId())).toList(), List.copyOf(selected.getValue())));
            } catch (IllegalArgumentException failure) {
                if (!Set.of("MISSING_PRODUCTION_ID", "AMBIGUOUS_PRODUCTION_PRODUCT").contains(failure.getMessage())) throw failure;
                identities.add(new Identity(selected.getKey(), Set.of(failure.getMessage()), List.copyOf(selected.getValue())));
            }
        }
        return new Selection(List.copyOf(trees), List.copyOf(identities));
    }
}
