package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisRequest {
    private String company;
    private LocalDate startDate;
    private LocalDate endDate;
    /** Used by BOM tracing; /trace derives its targets from sales in the requested period. */
    private List<String> targets;
}
