package dev.margintrace.margin_attribution_backend.algorithm.model;

import java.util.Map;

/** One shared graph and source-to-target node-index paths grouped by target inventory ID. */
public record CsrResult(
        CsrGraph csrGraph,
        Map<String, int[][]> pathsByTarget
) {
}
