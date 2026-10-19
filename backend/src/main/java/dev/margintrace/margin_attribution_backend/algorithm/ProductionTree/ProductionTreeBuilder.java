package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * Two-pass Java production-ID construction using an independently supplied actual CSR.
 * The loader must return whole instances,
 * including rows outside selection dates. No BOM, price layers or FIFO are read.
 * Missing evidence remains explicit; expansion uses only named upstream IDs and
 * declared homogeneous shares. Decimal multiplication and addition are exact.
 */
public final class ProductionTreeBuilder {
    private record Key(
            String position,
            String material,
            String unit,
            String source,
            BigDecimal share,
            MaterialKind kind,
            String unknownUnitRow,
            String graphEdgeId) {}

    private static final class Group {
        final Key key;
        final List<Issue> issues = new ArrayList<>();

        Group(Key key) {
            this.key = key;
        }
    }

    private static final class Node {
        int index, parent;
        String id, material, production, position, unit, source;
        MaterialKind kind;
        BigDecimal share, quantity, amount, sourceQuantity, sourceCost, scale;
        BigDecimal totalCost,
                knownTotalCost,
                materialSubtotal,
                laborCost,
                overheadCost,
                sourceTotalCost,
                valuationDifference;
        BigDecimal edgeLabor, edgeOverhead;
        String graphNodeId, graphEdgeId, currency, costScope;
        boolean costComplete, flowAmountComplete, flowQuantityComplete, flowUnitComplete;
        boolean edgeLaborComplete = true, edgeOverheadComplete = true;
        List<String> allocations = new ArrayList<>();
        List<String> lines = new ArrayList<>(), documents = new ArrayList<>();
        Set<String> statuses = new TreeSet<>();

        Occurrence freeze() {
            return new Occurrence(
                    index,
                    id,
                    material,
                    production,
                    position,
                    unit,
                    kind,
                    source,
                    text(share),
                    text(quantity),
                    text(amount),
                    text(sourceQuantity),
                    text(sourceCost),
                    List.copyOf(lines),
                    List.copyOf(documents),
                    Set.copyOf(statuses),
                    text(totalCost),
                    text(knownTotalCost),
                    text(materialSubtotal),
                    text(laborCost),
                    text(overheadCost),
                    currency,
                    costScope,
                    costComplete,
                    text(sourceTotalCost),
                    source == null ? null : text(totalCost),
                    text(valuationDifference));
        }
    }

    private record Output(
            BigDecimal quantity, BigDecimal cost, String unit, boolean comparable, String currency, String costScope) {}

    /**
     * Build one instance, rejecting blank IDs, absent rows or multiple products.
     * Returns inspectable incomplete trees for missing lineage/quantity evidence.
     * The graph is validated and traversed before terminal/material queries. The
     * production loader returns whole receiving instances; the material loader returns
     * every candidate receiver without date filtering. Both preserve composite source
     * identity. Returns original candidates, scope, decisions, exact projections and
     * independent topology/lineage/cost completeness. Malformed CSR, conflicting
     * source identities or overdrawn allocation pools raise IllegalArgumentException.
     */
    public ProductionTree build(
            String productionId,
            Evidence evidence,
            ActualGraph graph,
            Function<String, List<Issue>> loader,
            Function<String, List<Issue>> materialLoader,
            List<String> references) {
        if (blank(productionId)) throw new IllegalArgumentException("MISSING_PRODUCTION_ID");
        validateEvidence(evidence);
        Map<String, Issue> sourceIssues = new TreeMap<>();
        Function<String, List<Issue>> originalLoader = loader;
        loader = id -> {
            List<Issue> loaded = originalLoader.apply(id);
            for (Issue issue : loaded) remember(sourceIssues, issue);
            return loaded;
        };
        List<Issue> rows = rows(productionId, loader);
        String product = product(rows);
        TopDownScope topDown =
                new ProductionScopeTraversal().traverse(graph, evidence.namespace(), productionId, product);
        Set<String> statuses = new TreeSet<>(topDown.statuses());
        // Query every terminal first, then advance in dependency order. Every available receiver is
        // retained before any production-specific occurrence is attributed.
        Set<String> queried = new HashSet<>();
        List<String> queryOrder = new ArrayList<>(topDown.terminalNodeIds());
        for (String nodeId : topDown.bottomUpNodeIds()) if (!queryOrder.contains(nodeId)) queryOrder.add(nodeId);
        for (String nodeId : queryOrder) {
            String material = topDown.permittedNodes().stream()
                    .filter(n -> n.nodeId().equals(nodeId))
                    .findFirst()
                    .orElseThrow()
                    .productId();
            if (queried.add(material))
                for (Issue candidate : materialLoader.apply(material)) {
                    if (!Objects.equals(candidate.materialId(), material))
                        throw new IllegalArgumentException("Incorrect material query result");
                    remember(sourceIssues, candidate);
                }
        }
        List<IssueDecision> decisions = new ArrayList<>();
        List<ProcessingDecision> processing = new ArrayList<>();
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
        root.graphNodeId = topDown.rootNodeId();
        root.scale = BigDecimal.ONE;
        root.quantity = output.quantity;
        nodes.add(root);
        expand(
                productionId,
                rows,
                evidence,
                loader,
                nodes,
                root,
                BigDecimal.ONE,
                new LinkedHashSet<>(),
                statuses,
                topDown,
                decisions);
        List<Node> receivingIssues =
                nodes.stream().filter(n -> n.parent == root.index).toList();
        BigDecimal amount = subtotal(receivingIssues.stream().map(n -> n.amount).toList());
        root.amount = amount;
        if (receivingIssues.stream().anyMatch(n -> n.statuses.contains("MISSING_MATERIAL_AMOUNT")))
            root.statuses.add("MISSING_MATERIAL_AMOUNT");
        if (blank(evidence.currency()) || blank(evidence.materialCostScope())) statuses.add("MISSING_COST_SCOPE");
        if (blank(evidence.totalCostScope())) statuses.add("MISSING_TOTAL_COST_SCOPE");
        else if (!Objects.equals(evidence.materialCostScope(), evidence.totalCostScope()))
            statuses.add("INCOMPATIBLE_NODE_COST_SCOPE");
        BigDecimal difference = null;
        if (output.comparable
                && amount != null
                && output.cost != null
                && !root.statuses.contains("MISSING_MATERIAL_AMOUNT")
                && !root.statuses.contains("INCOMPLETE_GRAPH_SCOPE")
                && !blank(evidence.currency())
                && !blank(evidence.materialCostScope())) {
            difference = output.cost.subtract(amount);
            if (difference.signum() != 0) statuses.add("OUTPUT_COST_DISCREPANCY");
        }
        for (Node node : nodes) statuses.addAll(node.statuses);
        if (sourceIssues.values().stream()
                .anyMatch(i -> graph.coverageStart() != null
                        && nodes.stream().anyMatch(n -> n.lines.contains(i.lineId()))
                        && (i.date().isBefore(graph.coverageStart()) || i.date().isAfter(graph.coverageEnd()))))
            statuses.add("INCOMPLETE_GRAPH_PERIOD_COVERAGE");
        Map<String, BigDecimal> sourceShares = new HashMap<>();
        for (Node node : nodes)
            if (node.source != null && node.share != null)
                sourceShares.merge(node.source, node.share.multiply(node.scale), BigDecimal::add);
        if (sourceShares.values().stream().anyMatch(v -> v.compareTo(BigDecimal.ONE) > 0))
            throw new IllegalArgumentException("SOURCE_SHARE_EXCEEDS_SOURCE_OUTPUT");
        attachProcessing(nodes, evidence, topDown, scoped(evidence.namespace(), productionId), processing, statuses);
        calculateCosts(root, nodes, evidence, statuses);
        for (Node node : nodes) statuses.addAll(node.statuses);
        Set<String> acceptedIds = new HashSet<>();
        decisions.stream().filter(IssueDecision::accepted).forEach(d -> acceptedIds.add(d.issueLineId()));
        Set<String> rejectedIds = new HashSet<>();
        decisions.stream().filter(d -> !d.accepted()).forEach(d -> rejectedIds.add(d.issueLineId()));
        for (Issue candidate : sourceIssues.values())
            if (!acceptedIds.contains(candidate.lineId()) && !rejectedIds.contains(candidate.lineId())) {
                String reason = candidateReason(candidate, evidence, topDown, sourceIssues);
                decisions.add(new IssueDecision(
                        candidate.lineId(),
                        graph.graphId(),
                        scoped(evidence.namespace(), productionId),
                        null,
                        null,
                        false,
                        reason,
                        text(candidate.quantity()),
                        text(candidate.amount())));
            }
        BigDecimal totalDifference = null;
        if (output.comparable
                && root.costComplete
                && output.cost != null
                && !blank(evidence.totalCostScope())
                && Objects.equals(output.currency, evidence.currency())
                && Objects.equals(output.costScope, evidence.totalCostScope())) {
            totalDifference = output.cost.subtract(root.totalCost);
            if (totalDifference.signum() != 0) statuses.add("TOTAL_OUTPUT_COST_DISCREPANCY");
        }
        boolean direct = Collections.disjoint(
                statuses,
                Set.of(
                        "INCOMPLETE_IMPORTED_COVERAGE",
                        "MISSING_MATERIAL",
                        "MISSING_UNIT",
                        "MISSING_MATERIAL_QUANTITY",
                        "MISSING_MATERIAL_AMOUNT",
                        "MISSING_COST_SCOPE",
                        "UNVERIFIED_OUTPUT_QUANTITY",
                        "MISSING_OUTPUT_QUANTITY",
                        "OUTPUT_QUANTITY_CONFLICT",
                        "OUTPUT_COST_CONFLICT",
                        "INVALID_OUTPUT_QUANTITY",
                        "MISSING_OUTPUT_RECEIPT_ID",
                        "INCOMPLETE_RELATIONSHIP",
                        "CYCLE",
                        "SELF_CONSUMPTION",
                        "RETURN_REQUIRES_EXPLICIT_NETTING_RULE",
                        "INCOMPLETE_GRAPH_SCOPE",
                        "INCOMPLETE_GRAPH_COVERAGE",
                        "INCOMPLETE_GRAPH_PERIOD_COVERAGE",
                        "AMBIGUOUS_GRAPH_RELATIONSHIP",
                        "CONFLICTING_RECEIVING_SOURCE_RELATIONSHIP"));
        boolean lineage = direct
                && Collections.disjoint(
                        statuses,
                        Set.of(
                                "UNRESOLVED_SOURCE_PRODUCTION",
                                "MISSING_MATERIAL_CLASSIFICATION",
                                "MISSING_SOURCE_SHARE",
                                "SOURCE_PRODUCT_MISMATCH",
                                "MISSING_SOURCE_PRODUCTION",
                                "AMBIGUOUS_SOURCE_PRODUCTION_PRODUCT",
                                "UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE",
                                "INCOMPATIBLE_SOURCE_OUTPUT_UNIT",
                                "SOURCE_SHARE_QUANTITY_CONFLICT"));
        int[] offsets = new int[nodes.size() + 1];
        int[] columns = new int[Math.max(0, nodes.size() - 1)];
        List<Edge> edges = new ArrayList<>();
        int count = 0;
        for (Node node : nodes) {
            offsets[node.index] = count;
            if (node.parent >= 0) {
                columns[count++] = node.parent;
                edges.add(new Edge(
                        node.index,
                        node.parent,
                        text(node.quantity),
                        text(node.amount),
                        List.copyOf(node.lines),
                        node.source,
                        text(node.share),
                        node.graphEdgeId,
                        text(node.edgeLabor),
                        text(node.edgeOverhead),
                        List.copyOf(node.allocations),
                        node.flowQuantityComplete && node.flowUnitComplete,
                        node.flowAmountComplete,
                        node.edgeLabor != null
                                && node.edgeOverhead != null
                                && node.edgeLaborComplete
                                && node.edgeOverheadComplete));
            }
        }
        offsets[nodes.size()] = count;
        return new ProductionTree(
                evidence.namespace(),
                productionId,
                scoped(evidence.namespace(), productionId),
                product,
                evidence.scope(),
                0,
                nodes.stream().map(Node::freeze).toList(),
                List.copyOf(edges),
                List.copyOf(sourceIssues.values()),
                offsets,
                columns,
                text(output.quantity),
                text(output.cost),
                text(amount),
                text(difference),
                Set.copyOf(statuses),
                direct,
                lineage,
                List.copyOf(references),
                topDown,
                decisions.stream()
                        .sorted(Comparator.comparing(IssueDecision::issueLineId)
                                .thenComparing(d -> Objects.toString(d.occurrenceId(), "")))
                        .toList(),
                List.copyOf(processing),
                text(root.totalCost),
                text(root.knownTotalCost),
                text(root.laborCost),
                text(root.overheadCost),
                root.costComplete,
                text(totalDifference));
    }

    /** Require an explicit namespace and scope; absent maps mean absent evidence. */
    public static void validateEvidence(Evidence e) {
        if (e == null || blank(e.namespace()) || e.scope() == null)
            throw new IllegalArgumentException("Explicit dataset namespace and topology scope are required");
        validateProcessing(e);
        for (IssueEvidence i : issueMap(e).values()) {
            if (i == null) throw new IllegalArgumentException("Null issue evidence");
            if (i.sourceShare() != null
                    && (i.sourceShare().signum() <= 0 || i.sourceShare().compareTo(BigDecimal.ONE) > 0))
                throw new IllegalArgumentException("Source shares must be in (0,1]");
            if (i.sourceShare() != null && blank(i.sourceProductionId()))
                throw new IllegalArgumentException("A source share requires an explicit source production ID");
        }
    }

    private void expand(
            String production,
            List<Issue> rows,
            Evidence e,
            Function<String, List<Issue>> loader,
            List<Node> nodes,
            Node parent,
            BigDecimal scale,
            Set<String> ancestors,
            Set<String> statuses,
            TopDownScope topDown,
            List<IssueDecision> decisions) {
        if (!ancestors.add(production)) {
            parent.statuses.add("CYCLE");
            return;
        }
        if (e.completeProductionIds() == null || !e.completeProductionIds().contains(production))
            statuses.add("INCOMPLETE_IMPORTED_COVERAGE");
        Map<Key, Group> groups = new HashMap<>();
        Map<String, Issue> originalByLine = new HashMap<>();
        rows.forEach(r -> originalByLine.put(r.lineId(), r));
        Map<String, Relationship> rowRelationships = new HashMap<>();
        for (Issue row : rows) {
            IssueEvidence x = issueMap(e).get(row.lineId());
            String unit = x == null ? null : x.unit();
            MaterialKind kind = x == null || x.kind() == null ? MaterialKind.UNKNOWN : x.kind();
            String position = x == null ? null : x.receivingIssueLineId();
            String receiver = blank(position)
                    ? parent.material
                    : originalByLine.containsKey(position)
                            ? originalByLine.get(position).materialId()
                            : null;
            String positionFailure = validatePosition(row, originalByLine, e, new HashSet<>());
            if (positionFailure != null) {
                statuses.add(positionFailure);
                statuses.add("INCOMPLETE_GRAPH_SCOPE");
                parent.statuses.add("INCOMPLETE_GRAPH_SCOPE");
                decisions.add(new IssueDecision(
                        row.lineId(),
                        topDown.graphId(),
                        scoped(e.namespace(), nodes.get(0).production),
                        null,
                        null,
                        false,
                        positionFailure,
                        text(row.quantity()),
                        text(row.amount())));
                continue;
            }
            Relationship receivingRelationship = blank(position)
                    ? null
                    : resolveRelationship(originalByLine.get(position), originalByLine, e, topDown, parent.graphNodeId);
            String expectedReceiverNode = blank(position)
                    ? parent.graphNodeId
                    : receivingRelationship == null ? null : receivingRelationship.materialNodeId();
            List<Relationship> matches = topDown.permittedEdges().stream()
                    .filter(r -> code(topDown, r.materialNodeId()).equals(row.materialId())
                            && code(topDown, r.receivingNodeId()).equals(receiver)
                            && r.receivingNodeId().equals(expectedReceiverNode)
                            && (x == null
                                    || blank(x.graphEdgeId())
                                    || x.graphEdgeId().equals(r.edgeId())))
                    .toList();
            if (matches.size() != 1) {
                String reason = blank(row.materialId())
                        ? "MISSING_MATERIAL"
                        : receiver == null
                                ? "INCOMPLETE_RELATIONSHIP"
                                : matches.size() > 1
                                        ? "AMBIGUOUS_GRAPH_RELATIONSHIP"
                                        : topDown.permittedNodes().stream().noneMatch(n -> n.productId()
                                                        .equals(row.materialId()))
                                                ? "MATERIAL_OUTSIDE_SCOPE"
                                                : "RELATIONSHIP_OUTSIDE_SCOPE";
                statuses.add(reason);
                if (row.quantity() == null) statuses.add("MISSING_MATERIAL_QUANTITY");
                if (row.amount() == null) statuses.add("MISSING_MATERIAL_AMOUNT");
                if (Objects.equals(row.materialId(), parent.material) && blank(position))
                    statuses.add("SELF_CONSUMPTION");
                statuses.add("INCOMPLETE_GRAPH_SCOPE");
                parent.statuses.add("INCOMPLETE_GRAPH_SCOPE");
                decisions.add(new IssueDecision(
                        row.lineId(),
                        topDown.graphId(),
                        scoped(e.namespace(), nodes.get(0).production),
                        null,
                        null,
                        false,
                        reason,
                        text(row.quantity()),
                        text(row.amount())));
                continue;
            }
            rowRelationships.put(row.lineId(), matches.get(0));
            Key key = new Key(
                    position,
                    row.materialId(),
                    unit,
                    x == null ? null : x.sourceProductionId(),
                    x == null || x.sourceShare() == null
                            ? null
                            : x.sourceShare().stripTrailingZeros(),
                    kind,
                    blank(unit) ? row.lineId() : null,
                    rowRelationships.get(row.lineId()).edgeId());
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
            Relationship relationship = rowRelationships.get(g.issues.get(0).lineId());
            node.graphNodeId = relationship.materialNodeId();
            node.graphEdgeId = relationship.edgeId();
            node.scale = scale;
            node.quantity =
                    scaledSubtotal(g.issues.stream().map(Issue::quantity).toList(), scale);
            node.amount = scaledSubtotal(g.issues.stream().map(Issue::amount).toList(), scale);
            node.flowAmountComplete = g.issues.stream().allMatch(i -> i.amount() != null);
            node.flowQuantityComplete = g.issues.stream().allMatch(i -> i.quantity() != null);
            node.flowUnitComplete = !blank(node.unit);
            for (Issue row : g.issues) {
                node.lines.add(row.lineId());
                node.documents.add(row.documentId());
                byLine.put(row.lineId(), node);
                decisions.add(new IssueDecision(
                        row.lineId(),
                        topDown.graphId(),
                        scoped(e.namespace(), nodes.get(0).production),
                        node.id,
                        node.graphEdgeId,
                        true,
                        "ACCEPTED_RECEIVING_ISSUE",
                        text(row.quantity() == null ? null : row.quantity().multiply(scale)),
                        text(row.amount() == null ? null : row.amount().multiply(scale))));
                if (row.quantity() == null) node.statuses.add("MISSING_MATERIAL_QUANTITY");
                if (row.amount() == null) node.statuses.add("MISSING_MATERIAL_AMOUNT");
                IssueEvidence x = issueMap(e).get(row.lineId());
                if ((row.quantity() != null && row.quantity().signum() < 0)
                        || (row.amount() != null && row.amount().signum() < 0)
                        || (x != null && !blank(x.reversalOfIssueLineId())))
                    node.statuses.add("RETURN_REQUIRES_EXPLICIT_NETTING_RULE");
            }
            if (blank(node.material)) node.statuses.add("MISSING_MATERIAL");
            if (blank(node.unit)) node.statuses.add("MISSING_UNIT");
            if (node.kind == MaterialKind.UNKNOWN) node.statuses.add("MISSING_MATERIAL_CLASSIFICATION");
            if (Objects.equals(node.material, parent.material) && blank(node.position))
                node.statuses.add("SELF_CONSUMPTION");
            nodes.add(node);
            added.add(node);
        }
        // Resolve validated positions after all grouped receiving occurrences exist.
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
            if (node.kind == MaterialKind.INTERMEDIATE
                    && blank(node.source)
                    && added.stream().noneMatch(n -> n.parent == node.index))
                node.statuses.add("UNRESOLVED_SOURCE_PRODUCTION");
            if (blank(node.source)) continue;
            if (node.share == null) node.statuses.add("MISSING_SOURCE_SHARE");
            if (ancestors.contains(node.source)) {
                node.statuses.add("CYCLE");
                continue;
            }
            if (e.scope() == Scope.DIRECT_CONSUMPTION) {
                node.statuses.add("UPSTREAM_NOT_EXPANDED_AT_DIRECT_SCOPE");
                continue;
            }
            if (node.share == null) continue;
            List<Issue> source;
            try {
                source = rows(node.source, loader);
            } catch (IllegalArgumentException ex) {
                if (!"MISSING_PRODUCTION_ID".equals(ex.getMessage())) throw ex;
                node.statuses.add("MISSING_SOURCE_PRODUCTION");
                continue;
            }
            String sourceProduct;
            try {
                sourceProduct = product(source);
            } catch (IllegalArgumentException ex) {
                node.statuses.add("AMBIGUOUS_SOURCE_PRODUCTION_PRODUCT");
                continue;
            }
            if (!Objects.equals(sourceProduct, node.material)) {
                node.statuses.add("SOURCE_PRODUCT_MISMATCH");
                continue;
            }
            if (added.stream().anyMatch(n -> n.parent == node.index)) {
                node.statuses.add("INCOMPLETE_RELATIONSHIP");
                continue; // Do not duplicate recorded step inputs with upstream inputs.
            }
            Output upstream = output(node.source, source, e, node.statuses);
            node.sourceQuantity = upstream.quantity;
            node.sourceCost = upstream.cost;
            if (!Objects.equals(node.unit, upstream.unit)) node.statuses.add("INCOMPATIBLE_SOURCE_OUTPUT_UNIT");
            else if (node.quantity != null
                    && upstream.quantity != null
                    && node.quantity.compareTo(upstream.quantity.multiply(scale).multiply(node.share)) != 0)
                node.statuses.add("SOURCE_SHARE_QUANTITY_CONFLICT");
            expand(
                    node.source,
                    source,
                    e,
                    loader,
                    nodes,
                    node,
                    scale.multiply(node.share),
                    ancestors,
                    statuses,
                    topDown,
                    decisions);
        }
        ancestors.remove(production);
    }

    private static String validatePosition(Issue row, Map<String, Issue> rows, Evidence e, Set<String> active) {
        if (!active.add(row.lineId())) return "CYCLE";
        IssueEvidence x = issueMap(e).get(row.lineId());
        if (x == null || blank(x.receivingIssueLineId())) return null;
        Issue receiver = rows.get(x.receivingIssueLineId());
        if (receiver == null) return "INCOMPLETE_RELATIONSHIP";
        IssueEvidence receiverEvidence = issueMap(e).get(receiver.lineId());
        if (receiverEvidence != null && !blank(receiverEvidence.sourceProductionId()))
            return "CONFLICTING_RECEIVING_SOURCE_RELATIONSHIP";
        return validatePosition(receiver, rows, e, active);
    }

    private static Relationship resolveRelationship(
            Issue row, Map<String, Issue> rows, Evidence e, TopDownScope scope, String rootNode) {
        if (row == null) return null;
        IssueEvidence x = issueMap(e).get(row.lineId());
        String receiverNode = rootNode;
        if (x != null && !blank(x.receivingIssueLineId())) {
            Relationship receiver = resolveRelationship(rows.get(x.receivingIssueLineId()), rows, e, scope, rootNode);
            if (receiver == null) return null;
            receiverNode = receiver.materialNodeId();
        }
        String target = receiverNode;
        List<Relationship> matches = scope.permittedEdges().stream()
                .filter(r -> r.receivingNodeId().equals(target)
                        && code(scope, r.materialNodeId()).equals(row.materialId())
                        && (x == null
                                || blank(x.graphEdgeId())
                                || x.graphEdgeId().equals(r.edgeId())))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void remember(Map<String, Issue> issues, Issue issue) {
        if (issue == null || blank(issue.lineId()) || issue.date() == null)
            throw new IllegalArgumentException("Missing composite issue identity");
        Issue previous = issues.putIfAbsent(issue.lineId(), issue);
        if (previous != null && !previous.equals(issue))
            throw new IllegalArgumentException("Conflicting composite issue identity");
    }

    private static String code(TopDownScope scope, String nodeId) {
        return scope.permittedNodes().stream()
                .filter(n -> n.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow()
                .productId();
    }

    private static String candidateReason(
            Issue issue, Evidence evidence, TopDownScope scope, Map<String, Issue> candidates) {
        if (blank(issue.productionId())) return "MISSING_PRODUCTION_ID";
        IssueEvidence x = issueMap(evidence).get(issue.lineId());
        String receiver = issue.productId();
        if (x != null && !blank(x.receivingIssueLineId())) {
            Issue position = candidates.get(x.receivingIssueLineId());
            if (position == null || !Objects.equals(position.productionId(), issue.productionId()))
                return "INCOMPLETE_RELATIONSHIP";
            receiver = position.materialId();
        }
        String receivingCode = receiver;
        if (scope.permittedNodes().stream().noneMatch(n -> n.productId().equals(receivingCode)))
            return "RECEIVER_OUTSIDE_SCOPE";
        if (scope.permittedEdges().stream()
                .noneMatch(r -> code(scope, r.materialNodeId()).equals(issue.materialId())
                        && code(scope, r.receivingNodeId()).equals(receivingCode)
                        && (x == null
                                || blank(x.graphEdgeId())
                                || x.graphEdgeId().equals(r.edgeId())))) return "RELATIONSHIP_OUTSIDE_SCOPE";
        return "RECEIVING_OR_SOURCE_PRODUCTION_OUTSIDE_ROOT_PATH";
    }

    private static List<ProcessingSource> sources(Evidence e) {
        return e.processingSources() == null ? List.of() : e.processingSources();
    }

    private static List<ProcessingAllocation> allocations(Evidence e) {
        return e.processingAllocations() == null ? List.of() : e.processingAllocations();
    }

    /** Reject duplicated/overdrawn original pools before any occurrence projections. */
    private static void validateProcessing(Evidence e) {
        Map<String, ProcessingSource> pools = new HashMap<>();
        for (ProcessingSource source : sources(e)) {
            if (source == null
                    || blank(source.sourceId())
                    || blank(source.productionId())
                    || pools.putIfAbsent(source.sourceId(), source) != null)
                throw new IllegalArgumentException("INVALID_PROCESSING_SOURCE_IDENTITY");
            nonnegative(source.labor());
            nonnegative(source.overhead());
        }
        Map<String, ProcessingAllocation> unique = new HashMap<>();
        for (ProcessingAllocation allocation : allocations(e)) {
            if (allocation == null || blank(allocation.allocationId()))
                throw new IllegalArgumentException("INVALID_PROCESSING_ALLOCATION_IDENTITY");
            ProcessingAllocation old = unique.putIfAbsent(allocation.allocationId(), allocation);
            if (old != null && !old.equals(allocation))
                throw new IllegalArgumentException("CONFLICTING_PROCESSING_ALLOCATION_IDENTITY");
            nonnegative(allocation.labor());
            nonnegative(allocation.overhead());
        }
        for (ProcessingSource source : sources(e)) {
            List<ProcessingAllocation> parts = unique.values().stream()
                    .filter(a -> Objects.equals(a.sourceId(), source.sourceId()))
                    .toList();
            BigDecimal labor =
                    subtotal(parts.stream().map(ProcessingAllocation::labor).toList());
            BigDecimal overhead =
                    subtotal(parts.stream().map(ProcessingAllocation::overhead).toList());
            if ((source.labor() != null && labor != null && labor.compareTo(source.labor()) > 0)
                    || (source.overhead() != null && overhead != null && overhead.compareTo(source.overhead()) > 0))
                throw new IllegalArgumentException("PROCESSING_ALLOCATION_EXCEEDS_SOURCE_TOTAL");
        }
    }

    private static void nonnegative(BigDecimal amount) {
        if (amount != null && amount.signum() < 0)
            throw new IllegalArgumentException("Negative processing costs require an explicit netting rule");
    }

    private void attachProcessing(
            List<Node> nodes,
            Evidence e,
            TopDownScope scope,
            String root,
            List<ProcessingDecision> decisions,
            Set<String> statuses) {
        Map<String, ProcessingSource> pools = new TreeMap<>();
        sources(e).forEach(p -> pools.put(p.sourceId(), p));
        Map<String, ProcessingAllocation> unique = new TreeMap<>();
        allocations(e).forEach(a -> unique.putIfAbsent(a.allocationId(), a));
        Set<String> accepted = new HashSet<>();
        for (ProcessingAllocation a : unique.values()) {
            ProcessingSource source = pools.get(a.sourceId());
            String reason = source == null
                    ? "MISSING_PROCESSING_SOURCE"
                    : !Objects.equals(a.productionId(), source.productionId())
                            ? "INCOMPATIBLE_PROCESSING_PRODUCTION"
                            : blank(a.allocationBasis())
                                    ? "UNSUPPORTED_PROCESSING_ALLOCATION"
                                    : blank(e.currency())
                                                    || blank(e.totalCostScope())
                                                    || !Objects.equals(a.currency(), e.currency())
                                                    || !Objects.equals(a.costScope(), e.totalCostScope())
                                                    || !Objects.equals(source.currency(), e.currency())
                                                    || !Objects.equals(source.costScope(), e.totalCostScope())
                                            ? "INCOMPATIBLE_PROCESSING_COST_SCOPE"
                                            : null;
            List<Node> targets = nodes.stream()
                    .filter(n -> n.parent >= 0
                            && Objects.equals(n.production, a.productionId())
                            && Objects.equals(n.position, a.receivingIssueLineId())
                            && a.issueLineIds() != null
                            && !a.issueLineIds().isEmpty()
                            && new HashSet<>(n.lines).equals(new HashSet<>(a.issueLineIds())))
                    .toList();
            if (reason == null && targets.isEmpty()) reason = "UNALLOCATED_PROCESSING_COST";
            if (reason != null) {
                decisions.add(
                        new ProcessingDecision(a, source, scope.graphId(), root, null, false, reason, null, null));
                if (nodes.stream().anyMatch(n -> n.production.equals(a.productionId()))) statuses.add(reason);
                flagReceivers(nodes, a.productionId(), reason);
                continue;
            }
            accepted.add(a.allocationId());
            for (Node n : targets) {
                BigDecimal labor = a.labor() == null ? null : a.labor().multiply(n.scale);
                BigDecimal overhead = a.overhead() == null ? null : a.overhead().multiply(n.scale);
                n.edgeLabor = add(n.edgeLabor, labor);
                n.edgeOverhead = add(n.edgeOverhead, overhead);
                n.allocations.add(a.allocationId());
                if (a.labor() == null) {
                    n.edgeLaborComplete = false;
                    n.statuses.add("MISSING_LABOR_ALLOCATION");
                }
                if (a.overhead() == null) {
                    n.edgeOverheadComplete = false;
                    n.statuses.add("MISSING_OVERHEAD_ALLOCATION");
                }
                decisions.add(new ProcessingDecision(
                        a,
                        source,
                        scope.graphId(),
                        root,
                        n.id,
                        true,
                        "ACCEPTED_EXPLICIT_EDGE_ALLOCATION",
                        text(labor),
                        text(overhead)));
            }
        }
        for (ProcessingSource source : pools.values()) {
            List<ProcessingAllocation> parts = unique.values().stream()
                    .filter(a -> accepted.contains(a.allocationId()) && Objects.equals(a.sourceId(), source.sourceId()))
                    .toList();
            boolean allocated = !parts.isEmpty()
                    && decimalEqual(
                            source.labor(),
                            subtotal(parts.stream()
                                    .map(ProcessingAllocation::labor)
                                    .toList()))
                    && decimalEqual(
                            source.overhead(),
                            subtotal(parts.stream()
                                    .map(ProcessingAllocation::overhead)
                                    .toList()));
            if (!allocated) {
                decisions.add(new ProcessingDecision(
                        null, source, scope.graphId(), root, null, false, "UNALLOCATED_PROCESSING_SOURCE", null, null));
                if (nodes.stream().anyMatch(n -> n.production.equals(source.productionId())))
                    statuses.add("UNALLOCATED_PROCESSING_COST");
                flagReceivers(nodes, source.productionId(), "UNALLOCATED_PROCESSING_COST");
            }
        }
    }

    private static void flagReceivers(List<Node> nodes, String productionId, String status) {
        for (Node incoming : nodes)
            if (incoming.parent >= 0 && Objects.equals(incoming.production, productionId))
                nodes.get(incoming.parent).statuses.add(status);
    }

    /** Finalise receivers in dependency order; descendant totals are never additive material flows. */
    private void calculateCosts(Node node, List<Node> nodes, Evidence e, Set<String> statuses) {
        List<Node> children = nodes.stream().filter(n -> n.parent == node.index).toList();
        node.currency = e.currency();
        node.costScope = children.isEmpty() ? e.materialCostScope() : e.totalCostScope();
        if (children.isEmpty()) {
            node.knownTotalCost = node.amount;
            node.costComplete = node.kind == MaterialKind.RAW
                    && node.amount != null
                    && Collections.disjoint(
                            node.statuses,
                            Set.of(
                                    "MISSING_MATERIAL_AMOUNT",
                                    "MISSING_MATERIAL_QUANTITY",
                                    "MISSING_UNIT",
                                    "RETURN_REQUIRES_EXPLICIT_NETTING_RULE",
                                    "CYCLE"))
                    && !blank(e.currency())
                    && !blank(e.materialCostScope());
        } else {
            for (Node child : children) calculateCosts(child, nodes, e, statuses);
            node.materialSubtotal =
                    subtotal(children.stream().map(n -> n.amount).toList());
            node.laborCost = subtotal(children.stream().map(n -> n.edgeLabor).toList());
            node.overheadCost =
                    subtotal(children.stream().map(n -> n.edgeOverhead).toList());
            node.knownTotalCost = add(add(node.materialSubtotal, node.laborCost), node.overheadCost);
            boolean materialAmountComplete = children.stream()
                    .allMatch(n -> n.amount != null
                            && n.flowAmountComplete
                            && !n.statuses.contains("RETURN_REQUIRES_EXPLICIT_NETTING_RULE"));
            boolean materialComplete = materialAmountComplete
                    && children.stream().allMatch(n -> n.flowQuantityComplete && n.flowUnitComplete);
            boolean laborComplete = children.stream().allMatch(n -> n.edgeLabor != null && n.edgeLaborComplete);
            boolean overheadComplete =
                    children.stream().allMatch(n -> n.edgeOverhead != null && n.edgeOverheadComplete);
            if (!laborComplete) node.statuses.add("MISSING_LABOR_ALLOCATION");
            if (!overheadComplete) node.statuses.add("MISSING_OVERHEAD_ALLOCATION");
            if (!materialAmountComplete) node.statuses.add("MISSING_MATERIAL_AMOUNT");
            else if (!materialComplete) node.statuses.add("INCOMPLETE_MATERIAL_FLOW");
            node.costComplete = materialComplete
                    && laborComplete
                    && overheadComplete
                    && !blank(e.currency())
                    && !blank(e.materialCostScope())
                    && Objects.equals(e.materialCostScope(), e.totalCostScope())
                    && e.completeProductionIds() != null
                    && children.stream().allMatch(n -> e.completeProductionIds().contains(n.production))
                    && Collections.disjoint(
                            node.statuses,
                            Set.of(
                                    "UNALLOCATED_PROCESSING_COST",
                                    "INCOMPATIBLE_PROCESSING_COST_SCOPE",
                                    "INCOMPLETE_GRAPH_SCOPE",
                                    "CYCLE",
                                    "INCOMPATIBLE_SOURCE_OUTPUT_UNIT",
                                    "SOURCE_SHARE_QUANTITY_CONFLICT"))
                    && Collections.disjoint(
                            statuses, Set.of("INCOMPLETE_GRAPH_COVERAGE", "INCOMPLETE_GRAPH_PERIOD_COVERAGE", "CYCLE"));
        }
        if (node.costComplete) node.totalCost = node.knownTotalCost;
        else node.statuses.add("INCOMPLETE_NODE_TOTAL_COST");
        if (!blank(node.source) && !children.isEmpty()) {
            node.sourceTotalCost =
                    node.totalCost == null ? null : node.totalCost.divide(node.scale.multiply(node.share));
            if (node.amount != null
                    && node.flowAmountComplete
                    && node.totalCost != null
                    && node.costComplete
                    && Objects.equals(e.materialCostScope(), e.totalCostScope()))
                node.valuationDifference = node.amount.subtract(node.totalCost);
        }
    }

    private static BigDecimal add(BigDecimal left, BigDecimal right) {
        return left == null ? right : right == null ? left : left.add(right);
    }

    private static List<Issue> rows(String id, Function<String, List<Issue>> loader) {
        Map<String, Issue> unique = new TreeMap<>();
        for (Issue row : loader.apply(id)) {
            if (!Objects.equals(id, row.productionId()))
                throw new IllegalArgumentException("Incorrect receiving production ID");
            if (blank(row.lineId()) || row.date() == null)
                throw new IllegalArgumentException("Missing composite issue identity");
            Issue old = unique.putIfAbsent(row.lineId(), row);
            if (old != null && !old.equals(row))
                throw new IllegalArgumentException("Conflicting composite issue identity");
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
        return new Output(
                quantity,
                cost,
                unit,
                o != null && o.comparableCostScope(),
                o == null ? null : o.currency(),
                o == null ? null : o.costScope());
    }

    private static BigDecimal resolveOutput(
            List<Issue> rows, OutputEvidence o, boolean quantity, Set<String> statuses) {
        String name = quantity ? "QUANTITY" : "COST";
        OutputInterpretation interpretation =
                o == null ? null : quantity ? o.quantityInterpretation() : o.costInterpretation();
        if (interpretation == null || interpretation == OutputInterpretation.UNVERIFIED) {
            statuses.add("UNVERIFIED_OUTPUT_" + name);
            return null;
        }
        List<BigDecimal> values = new ArrayList<>();
        if (interpretation == OutputInterpretation.RECEIPTS) {
            Map<String, Receipt> unique = new LinkedHashMap<>();
            for (Receipt r : o.receipts() == null ? List.<Receipt>of() : o.receipts()) {
                if (r == null || blank(r.receiptId())) {
                    statuses.add("MISSING_OUTPUT_RECEIPT_ID");
                    continue;
                }
                Receipt previous = unique.putIfAbsent(r.receiptId(), r);
                if (previous != null
                        && (!decimalEqual(previous.quantity(), r.quantity())
                                || !decimalEqual(previous.cost(), r.cost()))) {
                    statuses.add("OUTPUT_" + name + "_CONFLICT");
                    return null;
                }
            }
            unique.values().forEach(r -> values.add(quantity ? r.quantity() : r.cost()));
            if (values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
                statuses.add("MISSING_OUTPUT_" + name);
                return null;
            }
            return subtotal(values);
        }
        rows.forEach(r -> values.add(quantity ? r.outputQuantity() : r.outputCost()));
        BigDecimal resolved = null;
        for (BigDecimal value : values) {
            if (value == null) continue;
            if (resolved != null && resolved.compareTo(value) != 0) {
                statuses.add("OUTPUT_" + name + "_CONFLICT");
                return null;
            }
            resolved = value;
        }
        if (resolved == null) statuses.add("MISSING_OUTPUT_" + name);
        return resolved;
    }

    private static Map<String, IssueEvidence> issueMap(Evidence e) {
        return e.issues() == null ? Map.of() : e.issues();
    }

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
    public static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    /** Test absent source identifiers without inventing replacement identities. */
    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String part(String s) {
        return s == null ? "-:" : s.length() + ":" + s;
    }
    /** Length-prefixed namespace/ID encoding avoids delimiter collisions. */
    public static String scoped(String namespace, String id) {
        return part(namespace) + part(id);
    }

    private static String keyText(Key k) {
        return part(k.position)
                + part(k.material)
                + part(k.unit)
                + part(k.source)
                + part(text(k.share))
                + part(k.kind.name())
                + part(k.unknownUnitRow)
                + part(k.graphEdgeId);
    }
}
