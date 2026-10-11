package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import java.util.*;

/** Explicit preceding-stage CSR fixtures, independent of the builder's output. */
public final class ProductionTreeFixtures {
    public static ActualGraph graph(String namespace, Map<String, String> roots, String... relationships) {
        Set<String> codes = new TreeSet<>(roots.values());
        for (String relation : relationships) codes.addAll(List.of(relation.split(">")));
        List<GraphNode> nodes = codes.stream().map(c -> new GraphNode(c, c)).toList();
        List<Integer> cols = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        int[] offsets = new int[nodes.size() + 1];
        List<String> unique = new TreeSet<>(List.of(relationships)).stream().toList();
        for (int i = 0; i < nodes.size(); i++) {
            offsets[i] = cols.size();
            for (String r : unique) {
                String[] pair = r.split(">");
                if (pair[0].equals(nodes.get(i).productId())) {
                    cols.add(new ArrayList<>(codes).indexOf(pair[1])); ids.add(r);
                }
            }
        }
        offsets[nodes.size()] = cols.size();
        return new ActualGraph("preceding-fixture", namespace, Direction.PRODUCT_TO_MATERIAL, true, nodes,
                offsets, cols.stream().mapToInt(Integer::intValue).toArray(), ids, roots, true, null, null);
    }

    /** Legacy test inputs declare their intended actual graph separately from execution. */
    public static ActualGraph graphForRows(String namespace, List<Issue> rows, Evidence evidence) {
        Map<String, String> roots = new TreeMap<>();
        Set<String> relationships = new TreeSet<>();
        Map<String, Issue> byLine = new HashMap<>(); rows.forEach(r -> byLine.put(r.lineId(), r));
        for (Issue r : rows) {
            if (r.productionId() != null && !r.productionId().isBlank() && r.productId() != null) roots.putIfAbsent(r.productionId(), r.productId());
            IssueEvidence x = evidence.issues() == null ? null : evidence.issues().get(r.lineId());
            String receiver = x != null && x.receivingIssueLineId() != null && byLine.containsKey(x.receivingIssueLineId()) ?
                    byLine.get(x.receivingIssueLineId()).materialId() : r.productId();
            if (receiver != null && r.materialId() != null) relationships.add(receiver + ">" + r.materialId());
        }
        return graph(namespace, roots, relationships.toArray(String[]::new));
    }
}
