package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;
import java.util.UUID;

/** Full paths that stopped because the cost difference exceeded the threshold. */
public record ReconciliationAnalysisResponse(UUID analysisId, List<ReconciliationPathEntry> paths) {
    public ReconciliationAnalysisResponse {
        paths = List.copyOf(paths);
    }
}
