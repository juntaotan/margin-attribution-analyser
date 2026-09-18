package dev.margintrace.margin_attribution_backend.algorithm.BOMPruner;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;

import java.math.BigDecimal;
import java.util.*;

/**
 * Traverses a CSR graph from a downstream node toward all reachable upstream nodes.
 *
 * <p>This initial framework propagates node positions only. Future versions can attach
 * a variance or embedding payload to each queued position and apply an allocation rule
 * when one node has multiple upstream predecessors.</p>
 */
public class UpstreamPropagator {

    /**
     * Visits the starting position and every node reachable through incoming edges.
     * Each position is returned once, in breadth-first downstream-to-upstream order.
     *
     * @param graph graph whose stored edges point from upstream to downstream
     * @param startPosition downstream position at which propagation begins
     * @return visited node positions, including {@code startPosition}
     */
    public List<Integer> adaptivePruning(
            CsrGraph graph,
            int startPosition,
            Map<Integer, Integer> comparablePoints,
            BigDecimal variance
    ) {
        List<Integer> result = new ArrayList<>();
        return result;
    }

    private void comparer(BigDecimal original, BigDecimal comparable, BigDecimal threshold){

    }
}
