package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import java.util.*;

/** Deterministic same-product/namespace all-pairs enumeration, including pairs within a period. */
public final class ProductionPairSelector {
    public record Pair(ProductionTree left, ProductionTree right) {}

    private record Group(String namespace, String product) {}

    /**
     * @param trees the production trees to compare
     * @return every pair of distinct production IDs with the same namespace and product, ordered by production ID;
     *     the first tree supplied for an ID is used
     */
    public List<Pair> select(List<ProductionTree> trees) {
        var groups = new TreeMap<Group, TreeMap<String, ProductionTree>>(
                Comparator.comparing(Group::namespace).thenComparing(Group::product));
        for (var tree : trees)
            groups.computeIfAbsent(new Group(tree.namespace(), tree.productId()), k -> new TreeMap<>())
                    .putIfAbsent(tree.productionId(), tree);
        var pairs = new ArrayList<Pair>();
        for (var group : groups.values()) {
            var ordered = new ArrayList<>(group.values());
            for (int i = 0; i < ordered.size(); i++)
                for (int j = i + 1; j < ordered.size(); j++) pairs.add(new Pair(ordered.get(i), ordered.get(j)));
        }
        return List.copyOf(pairs);
    }
}
