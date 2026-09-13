package dev.margintrace.margin_attribution_backend.analysis.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AnalysisResults {
    private UUID analysisId;
    /** Entries of the traced Node-to-direct-downstream-nodes adjacency map. */
    private List<AnalysisAdjacencyEntry> results;
}
