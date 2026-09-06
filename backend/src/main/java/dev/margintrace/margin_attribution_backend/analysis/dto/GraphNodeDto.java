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
public class GraphNodeDto {
    private String id;
    private String name;
    private String category;
    private BigDecimal quantity;
    private String department;
    private BigDecimal cost;
}
