package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

/**
 * This class implements the topological sort algorithm to build a dependency graph.
 */
public class TopologicalSort {
    private static final Comparator<Node> NODE_ORDER = (left, right) -> {
        int inventoryIdOrder = left.inventoryId().compareTo(right.inventoryId());
        if (inventoryIdOrder != 0) {
            return inventoryIdOrder;
        }
        return left.quantity().compareTo(right.quantity());
    };

    /**
     * Converts material-to-product dependencies to compressed sparse row format.
     * An input entry represents edges from its material key to every product in its value.
     *
     * @param materialUsage material nodes mapped to their downstream product nodes
     * @return the dependency graph in CSR format
     */
    public CsrGraph offsetDependencies(Map<Node, List<Node>> materialUsage) {
        Objects.requireNonNull(materialUsage, "materialUsage must not be null");

        Set<Node> orderedNodes = new TreeSet<>(NODE_ORDER);
        // Count the number of edges in the graph to allocate arrays of the correct size.
        int edgeCount = 0;
        for (Map.Entry<Node, List<Node>> entry : materialUsage.entrySet()) {

            // Validate that the material and product list are not null.
            Node material = Objects.requireNonNull(entry.getKey(),"materialUsage must not contain a null material");
            List<Node> products = Objects.requireNonNull(entry.getValue(),"materialUsage must not contain a null product list");

            // Store the material and its products to the ordered set of nodes
            orderedNodes.add(material);
            for (Node product : products) {
                orderedNodes.add(Objects.requireNonNull(product,"materialUsage must not contain a null product"));
            }

            // Calculate the number of edges in the graph by adding the number of products for this material.
            edgeCount = Math.addExact(edgeCount, products.size());
        }

        Node[] nodes = orderedNodes.toArray(Node[]::new);
        Map<Node, Integer> nodeIndexes = new HashMap<>();
        for (int nodeIndex = 0; nodeIndex < nodes.length; nodeIndex++) {
            nodeIndexes.put(nodes[nodeIndex], nodeIndex);
        }

        int[] pred = new int[edgeCount];
        int[] succ = new int[edgeCount];
        int edgeIndex = 0;

        // Group edges by predecessor so each node occupies one contiguous CSR range.
        for (int predecessorIndex = 0; predecessorIndex < nodes.length; predecessorIndex++) {
            List<Node> products = materialUsage.get(nodes[predecessorIndex]);
            if (products == null) {
                continue;
            }

            for (Node product : products) {
                pred[edgeIndex] = predecessorIndex;
                succ[edgeIndex] = nodeIndexes.get(product);
                edgeIndex++;
            }
        }

        int[] offset = caculateOffsets(nodes.length, pred);
        return new CsrGraph(nodes, offset, succ);
    }

    private int[] caculateOffsets(int nodeCount, int[] pred) {
        int[] offset = new int[nodeCount + 1];
        int[] outdegree = calculateOutdegree(nodeCount, pred);

        int accumulatedOffset = 0;
        for (int index = 0; index < outdegree.length; index++) {
            offset[index] = accumulatedOffset;
            accumulatedOffset += outdegree[index];
        }
        offset[outdegree.length] = accumulatedOffset;
        return offset;
    }

    /**
     * Calculates each node's outdegree in node-index order.
     *
     * @param nodeCount total number of graph nodes
     * @param pred predecessor node IDs, with one entry per outgoing edge
     * @return outdegrees aligned with the node array
     */
    private int[] calculateOutdegree(int nodeCount, int[] pred) {
        Objects.requireNonNull(pred, "pred must not be null");

        int[] outdegree = new int[nodeCount];
        for (int predecessor : pred) {
            outdegree[predecessor]++;
        }
        return outdegree;
    }
}
