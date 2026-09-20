package dev.margintrace.margin_attribution_backend.analysis.service.impl;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.TopologicalSort;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisGraphResponse;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisRequest;
import dev.margintrace.margin_attribution_backend.analysis.dto.GraphEdgeDto;
import dev.margintrace.margin_attribution_backend.analysis.dto.GraphNodeDto;
import dev.margintrace.margin_attribution_backend.analysis.service.AnalysisService;
import dev.margintrace.margin_attribution_backend.warehouse.model.Production;
import dev.margintrace.margin_attribution_backend.warehouse.repository.ProductionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisServiceImpl implements AnalysisService {

    private final ProductionRepository productionRepository;
    private final AttributionPartitionReader reader;
    private final TopologicalSort topologicalSort;

    @Override
    public AnalysisGraphResponse analyze(AnalysisRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            return emptyResponse("Start date and end date are required");
        }

        List<Production> productions = productionRepository.findAllByDateBetweenOrderByDateAsc(
                request.getStartDate(),
                request.getEndDate()
        );

        if (productions.isEmpty()) {
            return emptyResponse("No production records found for the given period");
        }

        List<Long> productionIds = productions.stream()
                .map(Production::getId)
                .toList();

        Map<Node, List<Node>> materialUsage;
        try {
            materialUsage = reader.readMaterialUsageForProductionIds(productionIds);
        } catch (Exception e) {
            log.error("Failed to read material usage for production IDs {}", productionIds, e);
            return emptyResponse("Error reading material usage: " + e.getMessage());
        }

        if (materialUsage == null) {
            return emptyResponse("No material consumption found for the given period");
        }

        // This endpoint displays consumption edges; the shared reader also returns
        // empty entries for products without materials so trace can retain them.
        Map<Node, List<Node>> consumedEdges = new LinkedHashMap<>();
        materialUsage.forEach((node, downstream) -> {
            if (!downstream.isEmpty()) {
                consumedEdges.put(node, downstream);
            }
        });
        if (consumedEdges.isEmpty()) {
            return emptyResponse("No material consumption found for the given period");
        }

        CsrGraph csrGraph = topologicalSort.offsetDependencies(consumedEdges);
        return mapToGraphResponse(csrGraph, consumedEdges, productions);
    }

    private AnalysisGraphResponse mapToGraphResponse(
            CsrGraph graph,
            Map<Node, List<Node>> materialUsage,
            List<Production> productions
    ) {
        Map<String, Production> prodByNo = new HashMap<>();
        for (Production p : productions) {
            prodByNo.putIfAbsent(p.getProductNo(), p);
        }

        Set<String> productNodeIds = new HashSet<>();
        for (List<Node> targets : materialUsage.values()) {
            if (targets != null) {
                for (Node t : targets) {
                    productNodeIds.add(t.inventoryId());
                }
            }
        }

        Node[] nodes = graph.nodes();
        List<GraphNodeDto> nodeDtos = new ArrayList<>();
        for (Node node : nodes) {
            boolean isProduct = productNodeIds.contains(node.inventoryId());
            Production prod = prodByNo.get(node.inventoryId());

            String category = isProduct ? "FINISHED_GOOD" : "RAW_MATERIAL";
            String department = prod != null ? prod.getDepartment() : (isProduct ? "Production" : "Warehouse");

            nodeDtos.add(GraphNodeDto.builder()
                    .id(node.inventoryId())
                    .name(node.inventoryId())
                    .category(category)
                    .quantity(node.quantity())
                    .department(department)
                    .cost(node.cost())
                    .build());
        }

        int[] offsets = graph.offset();
        int[] successors = graph.successors();
        List<GraphEdgeDto> edgeDtos = new ArrayList<>();

        for (int i = 0; i < nodes.length; i++) {
            String sourceId = nodes[i].inventoryId();
            int start = offsets[i];
            int end = offsets[i + 1];
            for (int edgeIdx = start; edgeIdx < end; edgeIdx++) {
                int succIdx = successors[edgeIdx];
                String targetId = nodes[succIdx].inventoryId();
                edgeDtos.add(GraphEdgeDto.builder()
                        .id(sourceId + "->" + targetId)
                        .source(sourceId)
                        .target(targetId)
                        .quantity(nodes[i].quantity())
                        .cost(nodes[i].cost())
                        .build());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNodes", nodeDtos.size());
        summary.put("totalEdges", edgeDtos.size());
        summary.put("analyzedAt", LocalDateTime.now().toString());

        return AnalysisGraphResponse.builder()
                .summary(summary)
                .nodes(nodeDtos)
                .edges(edgeDtos)
                .build();
    }

    private AnalysisGraphResponse emptyResponse(String message) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalNodes", 0);
        summary.put("totalEdges", 0);
        summary.put("message", message);
        summary.put("analyzedAt", LocalDateTime.now().toString());

        return AnalysisGraphResponse.builder()
                .summary(summary)
                .nodes(List.of())
                .edges(List.of())
                .build();
    }
}
