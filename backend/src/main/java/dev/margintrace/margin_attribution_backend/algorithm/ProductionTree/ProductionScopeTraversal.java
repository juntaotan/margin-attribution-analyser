package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeBuilder.blank;

import java.util.*;

/** Validates the preceding actual CSR and records every root-to-terminal path before issue queries. */
public final class ProductionScopeTraversal {
    /**
     * Traverse one explicitly selected root, transposing material-to-product CSR.
     * Malformed identities/arrays or unverified BOM scope are rejected. Cycles and
     * missing coverage return inspectable incomplete scope, never a complete terminal set.
     */
    public TopDownScope traverse(ActualGraph graph, String namespace, String productionId, String productId) {
        validate(graph, namespace);
        String rootId = graph.roots().get(productionId);
        int root = -1;
        for (int i = 0; i < graph.nodes().size(); i++)
            if (graph.nodes().get(i).nodeId().equals(rootId)) root = i;
        if (root < 0 || !Objects.equals(graph.nodes().get(root).productId(), productId))
            throw new IllegalArgumentException("MISSING_OR_INCOMPATIBLE_GRAPH_ROOT");
        List<List<Integer>> children = new ArrayList<>();
        Map<String, Relationship> relationships = new HashMap<>();
        for (int i = 0; i < graph.nodes().size(); i++) children.add(new ArrayList<>());
        for (int i = 0; i < graph.nodes().size(); i++)
            for (int k = graph.rowOffsets()[i]; k < graph.rowOffsets()[i + 1]; k++) {
                int j = graph.colIndices()[k];
                int material = graph.direction() == Direction.MATERIAL_TO_PRODUCT ? i : j;
                int receiver = graph.direction() == Direction.MATERIAL_TO_PRODUCT ? j : i;
                children.get(receiver).add(material);
                relationships.put(
                        receiver + ":" + material,
                        new Relationship(
                                graph.edgeIds().get(k),
                                graph.nodes().get(material).nodeId(),
                                graph.nodes().get(receiver).nodeId()));
            }
        children.forEach(
                c -> c.sort(Comparator.comparing(i -> graph.nodes().get(i).nodeId())));
        Set<Integer> visited = new LinkedHashSet<>(),
                terminals = new LinkedHashSet<>(),
                bottomUp = new LinkedHashSet<>();
        Map<String, Relationship> permitted = new TreeMap<>();
        List<List<String>> paths = new ArrayList<>();
        Set<String> statuses = new TreeSet<>();
        if (!graph.completeCoverage()) statuses.add("INCOMPLETE_GRAPH_COVERAGE");
        walk(
                root,
                graph,
                children,
                relationships,
                new LinkedHashSet<>(),
                new ArrayList<>(),
                visited,
                terminals,
                bottomUp,
                permitted,
                paths,
                statuses);
        return new TopDownScope(
                graph.graphId(),
                rootId,
                visited.stream().map(graph.nodes()::get).toList(),
                List.copyOf(permitted.values()),
                List.copyOf(paths),
                terminals.stream().map(i -> graph.nodes().get(i).nodeId()).toList(),
                bottomUp.stream().map(i -> graph.nodes().get(i).nodeId()).toList(),
                Set.copyOf(statuses),
                graph);
    }

    private void walk(
            int node,
            ActualGraph graph,
            List<List<Integer>> children,
            Map<String, Relationship> relationships,
            Set<Integer> active,
            List<String> path,
            Set<Integer> visited,
            Set<Integer> terminals,
            Set<Integer> bottomUp,
            Map<String, Relationship> permitted,
            List<List<String>> paths,
            Set<String> statuses) {
        visited.add(node);
        if (!active.add(node)) {
            statuses.add("CYCLE");
            return;
        }
        path.add(graph.nodes().get(node).nodeId());
        if (children.get(node).isEmpty()) {
            terminals.add(node);
            paths.add(List.copyOf(path));
        }
        for (int child : children.get(node)) {
            Relationship r = relationships.get(node + ":" + child);
            permitted.put(r.edgeId(), r);
            if (node == child) statuses.add("SELF_CONSUMPTION");
            walk(
                    child,
                    graph,
                    children,
                    relationships,
                    active,
                    path,
                    visited,
                    terminals,
                    bottomUp,
                    permitted,
                    paths,
                    statuses);
        }
        path.remove(path.size() - 1);
        active.remove(node);
        bottomUp.add(node);
    }

    /** Validate complete array alignment and stable node/edge identities before accessing CSR indices. */
    public static void validate(ActualGraph g, String namespace) {
        if (g == null) throw new IllegalArgumentException("MISSING_PRECEDING_ACTUAL_GRAPH");
        if (blank(g.graphId())
                || !Objects.equals(namespace, g.namespace())
                || g.direction() == null
                || !g.verifiedActual()) throw new IllegalArgumentException("UNVERIFIED_OR_INCOMPATIBLE_ACTUAL_GRAPH");
        if (g.nodes() == null
                || g.nodes().isEmpty()
                || g.rowOffsets() == null
                || g.colIndices() == null
                || g.edgeIds() == null
                || g.roots() == null
                || g.rowOffsets().length != g.nodes().size() + 1
                || g.rowOffsets()[0] != 0
                || g.rowOffsets()[g.nodes().size()] != g.colIndices().length
                || g.edgeIds().size() != g.colIndices().length)
            throw new IllegalArgumentException("MALFORMED_ACTUAL_CSR");
        if ((g.coverageStart() == null) != (g.coverageEnd() == null)
                || (g.coverageStart() != null && g.coverageStart().isAfter(g.coverageEnd())))
            throw new IllegalArgumentException("INVALID_GRAPH_COVERAGE_PERIOD");
        Set<String> nodes = new HashSet<>(), edges = new HashSet<>();
        for (GraphNode n : g.nodes())
            if (n == null || blank(n.nodeId()) || blank(n.productId()) || !nodes.add(n.nodeId()))
                throw new IllegalArgumentException("INVALID_GRAPH_NODE_IDENTITY");
        for (String id : g.edgeIds())
            if (blank(id) || !edges.add(id)) throw new IllegalArgumentException("INVALID_GRAPH_EDGE_IDENTITY");
        for (var root : g.roots().entrySet())
            if (blank(root.getKey()) || !nodes.contains(root.getValue()))
                throw new IllegalArgumentException("INVALID_GRAPH_ROOT_IDENTITY");
        for (int i = 0; i < g.nodes().size(); i++) {
            if (g.rowOffsets()[i] < 0
                    || g.rowOffsets()[i] > g.rowOffsets()[i + 1]
                    || g.rowOffsets()[i + 1] > g.colIndices().length)
                throw new IllegalArgumentException("MALFORMED_ACTUAL_CSR");
            Set<Integer> successors = new HashSet<>();
            for (int k = g.rowOffsets()[i]; k < g.rowOffsets()[i + 1]; k++)
                if (g.colIndices()[k] < 0
                        || g.colIndices()[k] >= g.nodes().size()
                        || !successors.add(g.colIndices()[k]))
                    throw new IllegalArgumentException("MALFORMED_ACTUAL_CSR");
        }
    }
}
