package dev.margintrace.margin_attribution_backend.analysis.dto;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class AnalysisResults {
    private UUID analysisId;
    private HashMap<String, List<Node[]>> analysisGraph;
}
