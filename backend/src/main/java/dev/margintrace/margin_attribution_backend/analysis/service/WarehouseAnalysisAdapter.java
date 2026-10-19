package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.MaterialContext;
import dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Adapts a frozen warehouse snapshot using explicit run declarations, without guessing supplying batches. */
public final class WarehouseAnalysisAdapter {
    private WarehouseAnalysisAdapter() {}

    /** Build direct-consumption evidence. order_no remains the receiving production ID. */
    public static AnalysisCommand prepare(AnalysisCommand command, AnalysisSourceLoader.Snapshot snapshot) {
        var comparison = command.comparison();
        if (comparison == null || comparison.warehouse() == null) return command;
        if (comparison.evidence() != null)
            throw new IllegalArgumentException("Choose warehouse declarations or explicit production evidence");
        var options = comparison.warehouse();
        if (blank(options.namespace()) || blank(options.currency()) || blank(options.costScope()))
            throw new IllegalArgumentException("Warehouse analysis requires namespace, currency and cost scope");
        var issues = new LinkedHashMap<String, ProductionTree.IssueEvidence>();
        var outputs = new LinkedHashMap<String, ProductionTree.OutputEvidence>();
        for (var row : snapshot.records().issues()) {
            var material = snapshot.materials().get(row.materialId());
            var product = snapshot.materials().get(row.productId());
            var kind = material == null
                    ? ProductionTree.MaterialKind.UNKNOWN
                    : "PURCHASED".equals(material.sourceKind())
                            ? ProductionTree.MaterialKind.RAW
                            : "INTERNAL".equals(material.sourceKind())
                                    ? ProductionTree.MaterialKind.INTERMEDIATE
                                    : ProductionTree.MaterialKind.UNKNOWN;
            issues.put(
                    row.lineId(),
                    new ProductionTree.IssueEvidence(
                            material == null ? null : material.unit(), kind, null, null, null, null));
            if (row.productionId() != null)
                outputs.put(
                        row.productionId(),
                        new ProductionTree.OutputEvidence(
                                options.quantityInterpretation(),
                                options.costInterpretation(),
                                List.of(),
                                product == null ? null : product.unit(),
                                true,
                                options.currency(),
                                options.costScope()));
        }
        var evidence = new ProductionTree.Evidence(
                options.namespace(),
                ProductionTree.Scope.DIRECT_CONSUMPTION,
                comparison.completeGraphCoverage() ? Set.copyOf(outputs.keySet()) : Set.of(),
                issues,
                outputs,
                options.currency(),
                options.costScope());
        return new AnalysisCommand(
                command.actualStartDate(),
                command.actualEndDate(),
                command.comparableStartDate(),
                command.comparableEndDate(),
                command.leafThreshold(),
                command.stopThreshold(),
                command.execution(),
                command.embedding(),
                new AnalysisCommand.Comparison(
                        evidence,
                        comparison.completeGraphCoverage(),
                        comparison.contextEvidence(),
                        comparison.embeddings(),
                        comparison.config()));
    }

    /**
     * Resolve local context from material attributes and occurrence-specific valuation.
     * Recorded amount/positive quantity takes precedence over the declared master cost;
     * division uses six decimal places and HALF_UP. Missing descriptions/suppliers stay missing.
     */
    public static MaterialContext.Evidence context(
            AnalysisSourceLoader.Snapshot snapshot,
            AnalysisCommand.WarehouseOptions options,
            ProductionTree tree,
            ProductionTree.Occurrence node) {
        if (options == null) return null;
        var material = snapshot.materials().get(node.materialId());
        if (material == null) return null;
        boolean root = node.index() == tree.rootIndex();
        String amount = root ? tree.recordedOutputCost() : node.amount();
        String quantity = root ? tree.outputQuantity() : node.quantity();
        BigDecimal cost = material.unitCost();
        String basis = cost == null ? null : "DECLARED_MATERIAL_MASTER_UNIT_COST";
        var refs = new ArrayList<>(node.issueLineIds());
        refs.add("material_master:" + material.code());
        if (amount != null && quantity != null && new BigDecimal(quantity).signum() > 0) {
            cost = new BigDecimal(amount).divide(new BigDecimal(quantity), 6, RoundingMode.HALF_UP);
            basis = root ? "RECORDED_OUTPUT_COST_PER_OUTPUT_QUANTITY" : "RECORDED_ISSUE_AMOUNT_PER_QUANTITY";
            if (root) refs.add("production:" + tree.productionId());
        }
        return new MaterialContext.Evidence(
                new MaterialContext.Description(
                        material.name(),
                        material.specification(),
                        material.category(),
                        List.of("material_master:" + material.code())),
                material.sourceKind(),
                blank(material.supplier()) ? List.of() : List.of(material.supplier()),
                cost,
                options.currency(),
                material.unit(),
                options.costScope(),
                basis,
                List.copyOf(refs),
                false);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
