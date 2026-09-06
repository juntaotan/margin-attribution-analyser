package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphEdgeDto {
    private String id;
    private String source;
    private String target;
    private BigDecimal quantity;
    private BigDecimal cost;
}
