package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisAdjacencyEntry;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Analyser {

    private final AttributionWorkflow attributionWorkflow;
    private final BillOfMaterialRepository billOfMaterialRepository;
    private final ProductionRepository productionRepository;

    /**
     * Runs the attribution workflow and turns its source-to-target index paths into
     * a Node-to-direct-downstream-nodes adjacency map. The graph is used only to
     * resolve path indexes; nodes and edges outside the traced paths are excluded.
     * Repeated nodes and edges from converging paths or multiple targets are merged.
     *
     * @param startDate inclusive start of the sales and production period
     * @param endDate inclusive end of the sales and production period
     * @return one entry per traced Node, including terminal nodes with no downstream nodes
     */
    public AnalysisResults analyser(LocalDate startDate, LocalDate endDate) {
        CsrResult csrResult = attributionWorkflow.trace(startDate, endDate);
        Node[] graphNodes = csrResult.csrGraph().nodes();
        Map<Node, LinkedHashSet<Node>> adjacency = new LinkedHashMap<>();

        // Each target has its own paths, but the frontend needs one combined graph.
        // LinkedHashMap retains first-seen order; LinkedHashSet removes repeated edges.
        for (int[][] targetPaths : csrResult.pathsByTarget().values()) {
            // A path is an ordered sequence of indexes into the shared CSR graph.
            // Process every path because two paths can share upstream sections.
            for (int[] path : targetPaths) {
                // Add every visited node as a key, including a one-node path and the
                // terminal target; these nodes have an empty downstream list if needed.
                for (int position = 0; position < path.length; position++) {
                    Node upstream = graphNodes[path[position]];
                    LinkedHashSet<Node> downstream = adjacency.computeIfAbsent(
                            upstream, ignored -> new LinkedHashSet<>());

                    // Consecutive positions form one direct upstream-to-downstream
                    // edge. A terminal node has no next position and keeps its key.
                    if (position + 1 < path.length) {
                        downstream.add(graphNodes[path[position + 1]]);
                    }
                }
            }
        }

        List<AnalysisAdjacencyEntry> entries = new ArrayList<>(adjacency.size());
        // JSON object keys cannot contain Node objects, so expose each map entry as
        // a pair while preserving the exact Node values and direct relationships.
        for (Map.Entry<Node, LinkedHashSet<Node>> entry : adjacency.entrySet()) {
            entries.add(new AnalysisAdjacencyEntry(
                    entry.getKey(), List.copyOf(entry.getValue())));
        }

        return AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.copyOf(entries))
                .build();
    }

    /**
     * Traces the multi-level Bill of Materials (BOM) hierarchy for the given target products.
     * Sub-materials are placed upstream pointing to their parent products downstream,
     * matching the layout convention used by the attribution graph.
     *
     * @param targets target product IDs; if null or empty, an empty result is returned
     * @return one adjacency entry per visited BOM node
     */
    public AnalysisResults traceBom(List<String> targets) {
        return traceBom(targets, null, null);
    }

    /**
     * Traces the multi-level Bill of Materials (BOM) hierarchy for the given target products.
     * When period dates are provided, planned quantities are scaled by the actual production
     * quantity of each product order in that period, matching the scale of actual consumption.
     *
     * @param targets target product IDs; if null or empty, an empty result is returned
     * @param startDate optional production period start
     * @param endDate optional production period end
     * @return one adjacency entry per visited BOM node
     */
    public AnalysisResults traceBom(List<String> targets, LocalDate startDate, LocalDate endDate) {
        if (targets == null || targets.isEmpty()) {
            return AnalysisResults.builder()
                    .analysisId(UUID.randomUUID())
                    .results(List.of())
                    .build();
        }

        Set<String> currentProducts = new LinkedHashSet<>();
        for (String target : targets) {
            if (target != null && !target.isBlank()) {
                currentProducts.add(target.trim());
            }
        }

        if (currentProducts.isEmpty()) {
            return AnalysisResults.builder()
                    .analysisId(UUID.randomUUID())
                    .results(List.of())
                    .build();
        }

        Map<String, BigDecimal> producedQuantities = new HashMap<>();
        if (startDate != null && endDate != null) {
            List<Production> productions = productionRepository
                    .findAllByDateBetweenOrderByDateAsc(startDate, endDate);
            for (Production p : productions) {
                producedQuantities.put(p.getProductNo(), p.getCompletedQuantity());
            }
        }

        Map<Node, LinkedHashSet<Node>> adjacency = new LinkedHashMap<>();
        Map<String, Node> nodesByInventoryId = new LinkedHashMap<>();
        Set<String> visitedProducts = new LinkedHashSet<>();

        // Initialize target product nodes (terminal nodes)
        for (String target : currentProducts) {
            BigDecimal qty = producedQuantities.getOrDefault(target, BigDecimal.ONE);
            Node targetNode = new Node(target, qty);
            nodesByInventoryId.put(target, targetNode);
            adjacency.computeIfAbsent(targetNode, ignored -> new LinkedHashSet<>());
        }

        while (!currentProducts.isEmpty()) {
            List<BillOfMaterial> boms = billOfMaterialRepository.findAllByProductNoIn(currentProducts);
            visitedProducts.addAll(currentProducts);
            currentProducts = new LinkedHashSet<>();

            for (BillOfMaterial bom : boms) {
                String parentId = bom.getProductNo();
                String materialId = bom.getMaterialNo();

                Node parentNode = nodesByInventoryId.computeIfAbsent(
                        parentId, id -> new Node(id, producedQuantities.getOrDefault(id, BigDecimal.ONE)));

                // Calculate planned total usage: parent quantity * unit usage
                BigDecimal plannedUsage = parentNode.quantity().multiply(bom.getMaterialUsage());
                Node materialNode = nodesByInventoryId.computeIfAbsent(
                        materialId, id -> new Node(id, plannedUsage));

                // Sub-material is upstream; parent product is downstream
                adjacency.computeIfAbsent(materialNode, ignored -> new LinkedHashSet<>()).add(parentNode);
                adjacency.computeIfAbsent(parentNode, ignored -> new LinkedHashSet<>());

                if (!visitedProducts.contains(materialId)) {
                    currentProducts.add(materialId);
                }
            }
        }

        List<AnalysisAdjacencyEntry> entries = new ArrayList<>(adjacency.size());
        for (Map.Entry<Node, LinkedHashSet<Node>> entry : adjacency.entrySet()) {
            entries.add(new AnalysisAdjacencyEntry(
                    entry.getKey(), List.copyOf(entry.getValue())));
        }

        return AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.copyOf(entries))
                .build();
    }
}
