package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Eligibility failures have no solver result. Successful execution still requires
 * inspecting each start's numerical status; stationarity does not establish a cause.
 * Tree evidence and row/column source mappings identify every correspondence entry.
 */
public record ProductionComparisonResponse(
        Set<String> statuses,
        List<String> missingVectors,
        Map<String, String> embeddingVersions,
        ProductionTreeResponse trees,
        FgwEngine.Result result,
        Boolean converged) {}
