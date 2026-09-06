package dev.margintrace.margin_attribution_backend.analysis.dto;

import java.time.LocalDate;
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
}
