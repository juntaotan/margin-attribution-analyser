package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * Pure Java production-ID construction. The loader must return whole instances,
 * including rows outside selection dates. No BOM, price layers or FIFO are read.
 * Missing evidence remains explicit; expansion uses only named upstream IDs and
 * declared homogeneous shares. Decimal multiplication and addition are exact.
 */
public final class ProductionTreeBuilder {
    private record Key(String position, String material, String unit, String source,
                       BigDecimal share, MaterialKind kind, String unknownUnitRow) {}
    private static final class Group {
        final Key key;
        final List<Issue> issues = new ArrayList<>();
        Group(Key key) { this.key = key; }
    }
    private static final class Node {
        int index, parent;
        String id, material, production, position, unit, source;
        MaterialKind kind;
        BigDecimal share, quantity, amount, sourceQuantity, sourceCost;
        List<String> lines = new ArrayList<>(), documents = new ArrayList<>();
        Set<String> statuses = new TreeSet<>();
        Occurrence freeze() {
            return new Occurrence(index, id, material, production, position, unit, kind, source,
                    text(share), text(quantity), text(amount), text(sourceQuantity), text(sourceCost),
                    List.copyOf(lines), List.copyOf(documents), Set.copyOf(statuses));
        }
    }
    private record Output(BigDecimal quantity, BigDecimal cost, String unit, boolean comparable) {}

    /**
     * Build one instance, rejecting blank IDs, absent rows or multiple products.
     * Returns inspectable incomplete trees for missing lineage/quantity evidence.
     * Loader results are deduplicated only by the composite issue identity.
     */
    public ProductionTree build(String productionId, Evidence evidence,
                                Function<String, List<Issue>> loader, List<String> references) {
        if (blank(productionId)) throw new IllegalArgumentException("MISSING_PRODUCTION_ID");
        validateEvidence(evidence);
        Map<String, Issue> sourceIssues = new TreeMap<>();
        Function<String, List<Issue>> originalLoader = loader;
        loader = id -> {
            List<Issue> loaded = originalLoader.apply(id);
            for (Issue issue : loaded) sourceIssues.putIfAbsent(issue.lineId(), issue);
            return loaded;
        };
        List<Issue> rows = rows(productionId, loader);
        String product = product(rows);
        Set<String> statuses = new TreeSet<>();
        Output output = output(productionId, rows, evidence, statuses);
        List<Node> nodes = new ArrayList<>();
        Node root = new Node();
        root.index = 0;
        root.parent = -1;
        root.id = scoped(evidence.namespace(), productionId) + "/root/" + part(product);
        root.material = product;
        root.production = productionId;
        root.unit = output.unit;
        root.kind = MaterialKind.INTERMEDIATE;
        root.quantity = output.quantity;
        nodes.add(root);
        expand(productionId, rows, evidence, loader, nodes, root, BigDecimal.ONE,
                new LinkedHashSet<>(), statuses);
        List<Node> receivingIssues = nodes.stream().filter(n -> n.parent == root.index).toList();
        BigDecimal amount = subtotal(receivingIssues.stream().map(n -> n.amount).toList());
        root.amount = amount;
        if (receivingIssues.stream().anyMatch(n -> n.statuses.contains("MISSING_MATERIAL_AMOUNT")))
            root.statuses.add("MISSING_MATERIAL_AMOUNT");
        if (blank(evidence.currency()) || blank(evidence.materialCostScope())) statuses.add("MISSING_COST_SCOPE");
        BigDecimal difference = null;
        if (output.comparable && amount != null && output.cost != null &&
                !root.statuses.contains("MISSING_MATERIAL_AMOUNT")) {
            difference = output.cost.subtract(amount);
            if (difference.signum() != 0) statuses.add("OUTPUT_COST_DISCREPANCY");
        }
        for (Node node : nodes) statuses.addAll(node.statuses);
        boolean direct = Collections.disjoint(statuses, Set.of(
                "INCOMPLETE_IMPORTED_COVERAGE", "MISSING_MATERIAL", "MISSING_UNIT",
                "MISSING_MATERIAL_QUANTITY", "MISSING_MATERIAL_AMOUNT", "MISSING_COST_SCOPE",
                "UNVERIFIED_OUTPUT_QUANTITY", "MISSING_OUTPUT_QUANTITY", "OUTPUT_QUANTITY_CONFLICT", "OUTPUT_COST_CONFLICT",
                "INVALID_OUTPUT_QUANTITY", "MISSING_OUTPUT_RECEIPT_ID", "INCOMPLETE_RELATIONSHIP", "CYCLE",
                "SELF_CONSUMPTION", "RETURN_REQUIRES_EXPLICIT_NETTING_RULE"));
        boolean lineage = direct && Collections.disjoint(statuses, Set.of(
                "UNRESOLVED_SOURCE_PRODUCTION", "MISSING_MATERIAL_CLASSIFICATION",
                "MISSING_SOURCE_SHARE", "SOURCE_PRODUCT_MISMATCH", "MISSING_SOURCE_PRODUCTION",
                "AMBIGUOUS_SOURCE_PRODUCTION_PRODUCT", "UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE"));
        int[] offsets = new int[nodes.size() + 1];
        int[] columns = new int[Math.max(0, nodes.size() - 1)];
        List<Edge> edges = new ArrayList<>();
        int count = 0;
        for (Node node : nodes) {
            offsets[node.index] = count;
            if (node.parent >= 0) {
                columns[count++] = node.parent;
                edges.add(new Edge(node.index, node.parent, text(node.quantity), text(node.amount),
                        List.copyOf(node.lines), node.source, text(node.share)));
            }
        }
        offsets[nodes.size()] = count;
        return new ProductionTree(evidence.namespace(), productionId, scoped(evidence.namespace(), productionId),
                product, evidence.scope(), 0, nodes.stream().map(Node::freeze).toList(), List.copyOf(edges),
                List.copyOf(sourceIssues.values()), offsets, columns, text(output.quantity), text(output.cost), text(amount), text(difference),
                Set.copyOf(statuses), direct, lineage, List.copyOf(references));
    }

    /** Require an explicit namespace and scope; absent maps mean absent evidence. */
    public static void validateEvidence(Evidence e) {
        if (e == null || blank(e.namespace()) || e.scope() == null)
            throw new IllegalArgumentException("Explicit dataset namespace and topology scope are required");
        for (IssueEvidence i : issueMap(e).values()) {
            if (i == null) throw new IllegalArgumentException("Null issue evidence");
            if (i.sourceShare() != null && (i.sourceShare().signum() <= 0 || i.sourceShare().compareTo(BigDecimal.ONE) > 0))
                throw new IllegalArgumentException("Source shares must be in (0,1]");
            if (i.sourceShare() != null && blank(i.sourceProductionId()))
                throw new IllegalArgumentException("A source share requires an explicit source production ID");
        }
    }

    private void expand(String production, List<Issue> rows, Evidence e,
                        Function<String, List<Issue>> loader, List<Node> nodes, Node parent,
                        BigDecimal scale, Set<String> ancestors, Set<String> statuses) {
        if (!ancestors.add(production)) { parent.statuses.add("CYCLE"); return; }
        if (e.completeProductionIds() == null || !e.completeProductionIds().contains(production))
            statuses.add("INCOMPLETE_IMPORTED_COVERAGE");
        Map<Key, Group> groups = new HashMap<>();
        for (Issue row : rows) {
            IssueEvidence x = issueMap(e).get(row.lineId());
            String unit = x == null ? null : x.unit();
            MaterialKind kind = x == null || x.kind() == null ? MaterialKind.UNKNOWN : x.kind();
            String position = x == null ? null : x.receivingIssueLineId();
            Key key = new Key(position, row.materialId(), unit, x == null ? null : x.sourceProductionId(),
                    x == null || x.sourceShare() == null ? null : x.sourceShare().stripTrailingZeros(), kind,
                    blank(unit) ? row.lineId() : null);
            groups.computeIfAbsent(key, Group::new).issues.add(row);
        }
        List<Group> ordered = new ArrayList<>(groups.values());
        ordered.sort(Comparator.comparing(g -> keyText(g.key)));
        Map<String, Node> byLine = new HashMap<>();
        List<Node> added = new ArrayList<>();
        for (Group g : ordered) {
            Node node = new Node();
            node.index = nodes.size();
            node.parent = parent.index;
            node.id = parent.id + "/issue/" + keyText(g.key);
            node.material = g.key.material;
            node.production = production;
            node.position = g.key.position;
            node.unit = g.key.unit;
            node.source = g.key.source;
            node.share = g.key.share;
            node.kind = g.key.kind;
            node.quantity = scaledSubtotal(g.issues.stream().map(Issue::quantity).toList(), scale);
            node.amount = scaledSubtotal(g.issues.stream().map(Issue::amount).toList(), scale);
            for (Issue row : g.issues) {
                node.lines.add(row.lineId());
                node.documents.add(row.documentId());
                byLine.put(row.lineId(), node);
                if (row.quantity() == null) node.statuses.add("MISSING_MATERIAL_QUANTITY");
                if (row.amount() == null) node.statuses.add("MISSING_MATERIAL_AMOUNT");
                IssueEvidence x = issueMap(e).get(row.lineId());
                if ((row.quantity() != null && row.quantity().signum() < 0) ||
                        (row.amount() != null && row.amount().signum() < 0) ||
                        (x != null && !blank(x.reversalOfIssueLineId())))
                    node.statuses.add("RETURN_REQUIRES_EXPLICIT_NETTING_RULE");
            }
            if (blank(node.material)) node.statuses.add("MISSING_MATERIAL");
            if (blank(node.unit)) node.statuses.add("MISSING_UNIT");
            if (node.kind == MaterialKind.UNKNOWN) node.statuses.add("MISSING_MATERIAL_CLASSIFICATION");
            if (Objects.equals(node.material, parent.material) && blank(node.position)) node.statuses.add("SELF_CONSUMPTION");
            nodes.add(node);
            added.add(node);
        }
        // Resolve recorded positions after all groups exist. Unknown/cyclic links
        // remain rooted for inspection, with explicit failure evidence.
        for (Node node : added) {
            if (!blank(node.position)) {
                Node receiver = byLine.get(node.position);
                if (receiver == null) node.statuses.add("INCOMPLETE_RELATIONSHIP");
                else {
                    Set<Integer> seen = new HashSet<>();
                    Node cursor = receiver;
                    while (cursor != parent && seen.add(cursor.index) && cursor != node)
                        cursor = nodes.get(cursor.parent);
                    if (cursor == node || cursor != parent) node.statuses.add("CYCLE");
                    else node.parent = receiver.index;
                }
            }
        }
        for (Node node : added) {
            if (node.kind == MaterialKind.INTERMEDIATE && blank(node.source) &&
                    added.stream().noneMatch(n -> n.parent == node.index))
                node.statuses.add("UNRESOLVED_SOURCE_PRODUCTION");
            if (blank(node.source)) continue;
            if (node.share == null) node.statuses.add("MISSING_SOURCE_SHARE");
            if (ancestors.contains(node.source)) { node.statuses.add("CYCLE"); continue; }
            if (e.scope() == Scope.DIRECT_CONSUMPTION) {
                node.statuses.add("UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE");
                continue;
            }
            if (node.share == null) continue;
            List<Issue> source;
            try { source = rows(node.source, loader); }
            catch (IllegalArgumentException ex) { node.statuses.add("MISSING_SOURCE_PRODUCTION"); continue; }
            String sourceProduct;
            try { sourceProduct = product(source); }
            catch (IllegalArgumentException ex) { node.statuses.add("AMBIGUOUS_SOURCE_PRODUCTION_PRODUCT"); continue; }
            if (!Objects.equals(sourceProduct, node.material)) { node.statuses.add("SOURCE_PRODUCT_MISMATCH"); continue; }
            if (added.stream().anyMatch(n -> n.parent == node.index)) {
                node.statuses.add("INCOMPLETE_RELATIONSHIP");
                continue; // Do not duplicate recorded step inputs with upstream inputs.
            }
            Output upstream = output(node.source, source, e, node.statuses);
            node.sourceQuantity = upstream.quantity;
            node.sourceCost = upstream.cost;
            expand(node.source, source, e, loader, nodes, node, scale.multiply(node.share),
                    ancestors, statuses);
        }
        ancestors.remove(production);
    }

    private static List<Issue> rows(String id, Function<String, List<Issue>> loader) {
        Map<String, Issue> unique = new TreeMap<>();
        for (Issue row : loader.apply(id)) {
            if (!Objects.equals(id, row.productionId())) throw new IllegalArgumentException("Incorrect receiving production ID");
            if (blank(row.lineId()) || row.date() == null) throw new IllegalArgumentException("Missing composite issue identity");
            Issue old = unique.putIfAbsent(row.lineId(), row);
            if (old != null && !old.equals(row)) throw new IllegalArgumentException("Conflicting composite issue identity");
        }
        if (unique.isEmpty()) throw new IllegalArgumentException("MISSING_PRODUCTION_ID");
        return List.copyOf(unique.values());
    }
    private static String product(List<Issue> rows) {
        Set<String> products = new HashSet<>();
        rows.forEach(r -> products.add(r.productId()));
        if (products.size() != 1 || blank(products.iterator().next()))
            throw new IllegalArgumentException("AMBIGUOUS_PRODUCTION_PRODUCT");
        return products.iterator().next();
    }
    private static Output output(String id, List<Issue> rows, Evidence e, Set<String> statuses) {
        OutputEvidence o = e.outputs() == null ? null : e.outputs().get(id);
        BigDecimal quantity = resolveOutput(rows, o, true, statuses);
        BigDecimal cost = resolveOutput(rows, o, false, statuses);
        String unit = o == null ? null : o.unit();
        if (blank(unit)) statuses.add("MISSING_UNIT");
        if (quantity != null && quantity.signum() <= 0) statuses.add("INVALID_OUTPUT_QUANTITY");
        return new Output(quantity, cost, unit, o != null && o.comparableCostScope());
    }
    private static BigDecimal resolveOutput(List<Issue> rows, OutputEvidence o, boolean quantity, Set<String> statuses) {
        String name = quantity ? "QUANTITY" : "COST";
        OutputInterpretation interpretation = o == null ? null :
                quantity ? o.quantityInterpretation() : o.costInterpretation();
        if (interpretation == null || interpretation == OutputInterpretation.UNVERIFIED) {
            statuses.add("UNVERIFIED_OUTPUT_" + name); return null;
        }
        List<BigDecimal> values = new ArrayList<>();
        if (interpretation == OutputInterpretation.RECEIPTS) {
            Map<String, Receipt> unique = new LinkedHashMap<>();
            for (Receipt r : o.receipts() == null ? List.<Receipt>of() : o.receipts()) {
                if (r == null || blank(r.receiptId())) { statuses.add("MISSING_OUTPUT_RECEIPT_ID"); continue; }
                Receipt previous = unique.putIfAbsent(r.receiptId(), r);
                if (previous != null && (!decimalEqual(previous.quantity(), r.quantity()) || !decimalEqual(previous.cost(), r.cost()))) {
                    statuses.add("OUTPUT_" + name + "_CONFLICT"); return null;
                }
            }
            unique.values().forEach(r -> values.add(quantity ? r.quantity() : r.cost()));
            if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
                statuses.add("MISSING_OUTPUT_" + name); return null;
            }
            return subtotal(values);
        }
        rows.forEach(r -> values.add(quantity ? r.outputQuantity() : r.outputCost()));
        BigDecimal resolved = null;
        for (BigDecimal value : values) {
            if (value == null) continue;
            if (resolved != null && resolved.compareTo(value) != 0) {
                statuses.add("OUTPUT_" + name + "_CONFLICT"); return null;
            }
            resolved = value;
        }
        if (resolved == null) statuses.add("MISSING_OUTPUT_" + name);
        return resolved;
    }
    private static Map<String, IssueEvidence> issueMap(Evidence e) { return e.issues() == null ? Map.of() : e.issues(); }
    private static boolean decimalEqual(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }
    private static BigDecimal subtotal(List<BigDecimal> values) {
        BigDecimal total = null;
        for (BigDecimal value : values) if (value != null) total = total == null ? value : total.add(value);
        return total;
    }
    private static BigDecimal scaledSubtotal(List<BigDecimal> values, BigDecimal scale) {
        BigDecimal sum = subtotal(values);
        return sum == null ? null : sum.multiply(scale);
    }
    /** Render exact decimal values for API/source text; missing values remain null. */
    public static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
    /** Test absent source identifiers without inventing replacement identities. */
    public static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String part(String s) { return s == null ? "-:" : s.length() + ":" + s; }
    /** Length-prefixed namespace/ID encoding avoids delimiter collisions. */
    public static String scoped(String namespace, String id) { return part(namespace) + part(id); }
    private static String keyText(Key k) {
        return part(k.position) + part(k.material) + part(k.unit) + part(k.source) + part(text(k.share)) +
                part(k.kind.name()) + part(k.unknownUnitRow);
    }
}
