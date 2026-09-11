package dev.margintrace.margin_attribution_backend.algorithm;

import dev.margintrace.margin_attribution_backend.algorithm.attribution.AttributionPartitionReader;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.MarginAttributionAlgorithm;
import dev.margintrace.margin_attribution_backend.algorithm.attribution.TopologicalSort;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Entry point for reading material usage, building a graph and tracing a target upstream.
 * Quantities remain the original recorded quantities; no quantity or cost allocation is performed.
 */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {
    private final AttributionPartitionReader reader;
    private final TopologicalSort graphBuilder;
    private final MarginAttributionAlgorithm algorithm;

    /**
     * @param startId inclusive production record ID, not a product number
     * @param endId inclusive production record ID, not a product number
     * @param targetInventoryId inventory/product number identifying the target in the built graph
     * @return the graph and source-to-target paths whose indexes refer to that graph's nodes
     * @throws IllegalArgumentException if the range is inverted, the target is blank, absent,
     *                                  or matches multiple nodes with different quantities or costs
     */
    public Result trace(long startId, long endId, String targetInventoryId) {
        if (startId > endId) {
            throw new IllegalArgumentException("startId must not be greater than endId");
        }
        if (targetInventoryId == null || targetInventoryId.isBlank()) {
            throw new IllegalArgumentException("targetInventoryId must not be blank");
        }

        Map<Node, List<Node>> materialUsage = reader.readMaterialUsage(startId, endId);
        CsrGraph graph = graphBuilder.offsetDependencies(materialUsage);
        int targetPosition = findTargetPosition(graph, targetInventoryId);
        int[][] paths = algorithm.reverseTracing(graph, targetPosition);
        return new Result(graph, targetPosition, paths);
    }

    private int findTargetPosition(CsrGraph graph, String targetInventoryId) {
        int position = -1;
        for (int index = 0; index < graph.nodes().length; index++) {
            if (!graph.nodes()[index].inventoryId().equals(targetInventoryId)) {
                continue;
            }
            if (position != -1) {
                throw new IllegalArgumentException(
                        "Target matches multiple graph nodes: " + targetInventoryId);
            }
            position = index;
        }
        if (position == -1) {
            throw new IllegalArgumentException("Target not found in graph: " + targetInventoryId);
        }
        return position;
    }

    /** Paths contain node indexes into graph.nodes(), including the target at the end. */
    public record Result(CsrGraph graph, int targetPosition, int[][] paths) {}
}
