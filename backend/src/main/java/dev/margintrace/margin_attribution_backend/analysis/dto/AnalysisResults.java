package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AnalysisResults {
    private UUID analysisId;
    /** Entries of the traced Node-to-direct-downstream-nodes adjacency map. */
    private List<AnalysisAdjacencyEntry> results;
}
