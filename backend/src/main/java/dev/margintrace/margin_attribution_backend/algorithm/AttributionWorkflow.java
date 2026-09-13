package dev.margintrace.margin_attribution_backend.algorithm;

import dev.margintrace.margin_attribution_backend.algorithm.attribution.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.MarginAttributionAlgorithm;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.TopologicalSort;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Entry point for reading material usage, building one graph and tracing targets upstream.
 * Quantities remain the original recorded quantities; no quantity or cost allocation is performed.
 */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {
    private final ProductionRepository productionRepository;
    private final AttributionPartitionReader reader;
    private final TopologicalSort graphBuilder;
    private final MarginAttributionAlgorithm algorithm;

    /**
     * Builds a shared CSR graph from productions inside an inclusive date period,
     * then traces each requested target through that graph. An empty or null target
     * list selects every product produced in the period. Returned path integers are
     * indexes into the single returned graph's node array, in source-to-target order.
     *
     * @param startDate inclusive first production date
     * @param endDate inclusive last production date
     * @param targets inventory IDs to trace, or empty/null for every period product
     * @return one graph and an ordered target-to-paths map
     * @throws IllegalArgumentException if dates are missing or inverted, a target is
     *                                  blank or absent from the selected productions,
     *                                  or one product has conflicting produced quantities
     */
    public CsrResult trace(LocalDate startDate, LocalDate endDate, List<String> targets) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        List<Production> productions = productionRepository
                .findAllByDateBetweenOrderByDateAsc(startDate, endDate);
        Set<String> selectedTargets = selectTargets(targets, productions);
        Map<String, Node> producedNodesById = new LinkedHashMap<>();

        // Use the exact IDs returned by the date query. An ID interval could contain
        // other production dates, and the reader joins materials to those exact orders.
        // At the same time, retain the produced Node for unambiguous target selection:
        // a component can also appear as a consumed-material Node with a different cost.
        List<Long> productionIds = new ArrayList<>(productions.size());
        for (Production production : productions) {
            productionIds.add(Objects.requireNonNull(
                    production.getId(), "Selected production must have an ID"));
            Node produced = new Node(production.getProductNo(), production.getCompletedQuantity());
            Node existing = producedNodesById.putIfAbsent(produced.inventoryId(), produced);
            if (existing != null && !existing.equals(produced)) {
                throw new IllegalArgumentException(
                        "Product has multiple produced nodes in period: " + produced.inventoryId());
            }
        }

        Map<Node, List<Node>> materialUsage = reader
                .readMaterialUsageForProductionIds(productionIds);
        CsrGraph graph = graphBuilder.offsetDependencies(
                connectProducedAndConsumedNodes(materialUsage, producedNodesById));
        Map<String, int[][]> pathsByTarget = new LinkedHashMap<>();

        // Reuse the same graph for every target, while retaining each target's paths
        // separately so the analyser can later assemble the desired adjacency map.
        for (String target : selectedTargets) {
            Node produced = producedNodesById.get(target);
            if (produced == null) {
                throw new IllegalArgumentException("Target not found in period: " + target);
            }
            int position = findTargetPosition(graph, produced);
            pathsByTarget.put(target, algorithm.reverseTracing(graph, position));
        }
        return new CsrResult(graph, Collections.unmodifiableMap(pathsByTarget));
    }

    /**
     * Connects a produced item to its later use as a material when both appear in
     * the period graph. Those are distinct Node values because production has no
     * recorded cost while consumption can carry one. Without this bridge, tracing
     * a finished product would stop at the consumed component and miss the inputs
     * used to produce it. The original recorded nodes and their values are kept.
     */
    private Map<Node, List<Node>> connectProducedAndConsumedNodes(
            Map<Node, List<Node>> materialUsage,
            Map<String, Node> producedNodesById
    ) {
        Map<Node, List<Node>> graphUsage = new LinkedHashMap<>();
        Map<String, List<Node>> consumedNodesById = new LinkedHashMap<>();

        // Copy the reader's edge lists because it may return immutable collections.
        // Index only material keys: these identify consumed instances of inventory.
        for (Map.Entry<Node, List<Node>> entry : materialUsage.entrySet()) {
            Node upstream = entry.getKey();
            graphUsage.put(upstream, new ArrayList<>(entry.getValue()));
            if (!producedNodesById.containsKey(upstream.inventoryId())
                    || producedNodesById.get(upstream.inventoryId()).equals(upstream)) {
                continue;
            }
            consumedNodesById.computeIfAbsent(
                    upstream.inventoryId(), ignored -> new ArrayList<>()).add(upstream);
        }

        // Add a stage edge from the produced node to every distinct consumed node
        // of that inventory ID. This preserves both recorded quantities and costs.
        for (Map.Entry<String, Node> entry : producedNodesById.entrySet()) {
            List<Node> consumedNodes = consumedNodesById.get(entry.getKey());
            if (consumedNodes == null) {
                continue;
            }
            List<Node> downstream = graphUsage.computeIfAbsent(
                    entry.getValue(), ignored -> new ArrayList<>());
            for (Node consumed : consumedNodes) {
                if (!downstream.contains(consumed)) {
                    downstream.add(consumed);
                }
            }
        }
        return graphUsage;
    }

    /**
     * Determines the target IDs without losing the caller's order. Omitted targets
     * expand to all products in the selected period; explicit duplicates are traced
     * only once, and explicit blank IDs fail before any graph work begins.
     */
    private Set<String> selectTargets(List<String> targets, List<Production> productions) {
        Set<String> selected = new LinkedHashSet<>();
        if (targets == null || targets.isEmpty()) {
            // Each production row is a candidate target. A product produced by more
            // than one selected order still needs only one target lookup and trace.
            for (Production production : productions) {
                selected.add(production.getProductNo());
            }
            return selected;
        }

        // Normalize user-supplied IDs once, before comparing them with graph nodes.
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("targets must not contain a blank inventory ID");
            }
            selected.add(target.trim());
        }
        return selected;
    }

    /**
     * Finds the graph position of the exact produced Node, rather than selecting a
     * consumed-material Node that can share its inventory ID but have another cost.
     */
    private int findTargetPosition(CsrGraph graph, Node target) {
        int position = -1;

        // Node equality covers ID, quantity and cost; a match is the produced item.
        for (int index = 0; index < graph.nodes().length; index++) {
            if (!graph.nodes()[index].equals(target)) {
                continue;
            }
            if (position != -1) {
                throw new IllegalArgumentException(
                        "Target matches multiple graph nodes: " + target.inventoryId());
            }
            position = index;
        }

        if (position == -1) {
            throw new IllegalArgumentException("Target not found in graph: " + target.inventoryId());
        }
        return position;
    }

}
