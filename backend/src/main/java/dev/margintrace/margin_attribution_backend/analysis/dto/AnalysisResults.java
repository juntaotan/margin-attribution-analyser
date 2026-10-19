package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;

/**
 * Traced adjacency entries with the ID of the analysis that produced them.
 */
@Data
@Builder
public class AnalysisResults {
    private UUID analysisId;
    /** Entries of the traced Node-to-direct-downstream-nodes adjacency map. */
    private List<AnalysisAdjacencyEntry> results;
}
