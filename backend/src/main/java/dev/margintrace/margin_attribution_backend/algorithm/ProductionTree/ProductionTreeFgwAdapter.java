package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import java.util.*;

/**
 * Semantic gate around the existing serial Java engine. It never adds attribute
 * penalties or modifies weights, distances or solver tolerances. A session is
 * constructed and consumed synchronously inside this call on its owning thread.
 */
public final class ProductionTreeFgwAdapter {
    public record Embedding(String version, double[] values) {
        public Embedding { if (values != null) values = values.clone(); }
    }
    public record Request(String leftProductionId, String rightProductionId, Map<String, Embedding> embeddings,
                          FgwEngine.Config config, List<double[]> initialMatrices) {}
    public record Result(Set<String> statuses, List<String> missingVectors, Map<String, String> embeddingVersions,
                         FgwEngine.Result result, Boolean converged) {}
    private final dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwComparator comparator;
    public ProductionTreeFgwAdapter() { this((input, config) -> new FgwEngine().compare(input, config)); }
    public ProductionTreeFgwAdapter(dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwComparator comparator) {
        this.comparator = comparator;
    }
    /**
     * Inspect identity, scope, completeness and vectors before numerical work.
     * Missing vectors are reported by occurrence ID. Invalid vector dimensions,
     * versions or nonfinite components are explicit statuses, never zero-filled.
     * Invalid mathematical configuration/initial matrices retain engine exceptions.
     */
    public Result compare(List<ProductionTree> trees, Request request) {
        Set<String> statuses = new TreeSet<>();
        ProductionTree left = find(trees, request.leftProductionId());
        ProductionTree right = find(trees, request.rightProductionId());
        if (left == null || right == null) statuses.add("MISSING_COMPARISON_PRODUCTION_ID");
        if (Objects.equals(request.leftProductionId(), request.rightProductionId())) statuses.add("SAME_PRODUCTION_ID");
        if (left != null && right != null) {
            if (!left.productId().equals(right.productId())) statuses.add("INCOMPATIBLE_PRODUCT");
            if (!left.namespace().equals(right.namespace())) statuses.add("INCOMPATIBLE_NAMESPACE");
            if (left.scope() != right.scope()) statuses.add("INCOMPATIBLE_TOPOLOGY_SCOPE");
        }
        List<String> missing = new ArrayList<>();
        Map<String, String> versions = new TreeMap<>();
        Map<String, Embedding> vectors = request.embeddings() == null ? Map.of() : request.embeddings();
        for (ProductionTree tree : new ProductionTree[] {left, right}) {
            if (tree == null) continue;
            if (!tree.directComplete()) statuses.add("INCOMPLETE_DIRECT_CONSUMPTION");
            if (tree.scope() == ProductionTree.Scope.FULL_LINEAGE && !tree.lineageComplete())
                statuses.add("INCOMPLETE_FULL_LINEAGE");
            for (ProductionTree.Occurrence node : tree.nodes()) {
                var vector = vectors.get(node.occurrenceId());
                if (vector == null || vector.values() == null) { missing.add(node.occurrenceId()); continue; }
                if (ProductionTreeBuilder.blank(vector.version())) statuses.add("MISSING_EMBEDDING_VERSION");
                else versions.put(node.occurrenceId(), vector.version());
                if (vector.values().length != FgwEngine.EMBEDDING_DIMENSION) statuses.add("INVALID_EMBEDDING_DIMENSION");
                for (double value : vector.values()) if (!Double.isFinite(value)) statuses.add("NONFINITE_EMBEDDING");
            }
        }
        if (!missing.isEmpty()) statuses.add("MISSING_EMBEDDINGS");
        if (new HashSet<>(versions.values()).size() > 1) statuses.add("INCOMPATIBLE_EMBEDDING_VERSIONS");
        if (!statuses.isEmpty()) return new Result(Set.copyOf(statuses),
                List.copyOf(missing), Map.copyOf(versions), null, null);
        FgwEngine.Config config = request.config() == null ? new FgwEngine.Config() : request.config();
        FgwEngine.Result result = comparator.compare(new FgwEngine.Comparison(graph(left, vectors), graph(right, vectors),
                request.initialMatrices() == null ? List.of() : request.initialMatrices()), config);
        FgwEngine.StartResult best = result.starts.get(result.bestStart);
        statuses.add(best.status.name());
        if (left.nodes().stream().anyMatch(n -> !n.costComplete()) || right.nodes().stream().anyMatch(n -> !n.costComplete()))
            statuses.add("INCOMPLETE_TOTAL_COST_EVIDENCE");
        return new Result(Set.copyOf(statuses), List.of(),
                Map.copyOf(versions), result, best.converged());
    }
    private static ProductionTree find(List<ProductionTree> trees, String id) {
        return trees.stream().filter(t -> Objects.equals(t.productionId(), id)).findFirst().orElse(null);
    }
    /** Copy validated occurrence vectors into final CSR order, retaining exact source text and empty FIFO references. */
    public static FgwEngine.Graph graph(ProductionTree tree, Map<String, Embedding> vectors) {
        double[] embeddings = new double[Math.multiplyExact(tree.nodes().size(), FgwEngine.EMBEDDING_DIMENSION)];
        List<FgwEngine.NodeSource> sources = new ArrayList<>();
        for (ProductionTree.Occurrence node : tree.nodes()) {
            System.arraycopy(vectors.get(node.occurrenceId()).values(), 0, embeddings,
                    node.index() * FgwEngine.EMBEDDING_DIMENSION, FgwEngine.EMBEDDING_DIMENSION);
            sources.add(new FgwEngine.NodeSource(node.occurrenceId(), node.materialId(), node.productionId(),
                    node.quantity(), node.amount(), node.issueLineIds(), List.of()));
        }
        return new FgwEngine.Graph(tree.productId(), tree.instanceId(), tree.rootIndex(),
                tree.rowOffsets(), tree.colIndices(), embeddings, sources);
    }
}
