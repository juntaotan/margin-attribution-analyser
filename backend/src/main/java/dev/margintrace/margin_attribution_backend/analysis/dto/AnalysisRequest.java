package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for period tracing or BOM tracing: an inclusive date range and, for BOM tracing, the target products.
 */
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
