package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisGraphResponse {
    private Map<String, Object> summary;
    private List<GraphNodeDto> nodes;
    private List<GraphEdgeDto> edges;
}
