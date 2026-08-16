package dev.margintrace.margin_attribution_backend.algorithm.attribution;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Collectors;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;

/**
 * Command-line example for selecting a target position and displaying all upstream paths.
 */
public final class ReverseTracingDemo {
    private ReverseTracingDemo() {
    }

    public static void main(String[] arguments) {
        int targetPosition = arguments.length == 0 ? 3 : Integer.parseInt(arguments[0]);
        CsrGraph graph = exampleGraph();

        System.out.println("Available nodes:");
        for (int position = 0; position < graph.nodes().length; position++) {
            System.out.printf("  %d = %s%n", position, graph.nodes()[position].inventoryId());
        }

        System.out.printf("%nTarget position: %d%n", targetPosition);
        int[][] paths = new MarginAttributionAlgorithm().reverseTracing(graph, targetPosition);

        System.out.println("Upstream paths:");
        for (int[] path : paths) {
            String positions = Arrays.toString(path);
            String nodes = Arrays.stream(path)
                    .mapToObj(position -> graph.nodes()[position].inventoryId())
                    .collect(Collectors.joining(" -> "));
            System.out.printf("  %s  %s%n", positions, nodes);
        }
    }

    private static CsrGraph exampleGraph() {
        Node[] nodes = {
                node("MATERIAL-A"),
                node("MATERIAL-B"),
                node("COMPONENT-C"),
                node("PRODUCT-D")
        };

        // MATERIAL-A -> COMPONENT-C -> PRODUCT-D
        // MATERIAL-B -> COMPONENT-C
        return new CsrGraph(
                nodes,
                new int[] {0, 1, 2, 3, 3},
                new int[] {2, 2, 3}
        );
    }

    private static Node node(String inventoryId) {
        return new Node(inventoryId, BigDecimal.ONE);
    }
}
