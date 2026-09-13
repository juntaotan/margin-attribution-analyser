package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.AttributionWorkflow;
import dev.margintrace.margin_attribution_backend.analysis.dto.AnalysisResults;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Analyser {

    private final AttributionWorkflow attributionWorkflow;

    /**
     * Starts the graph and path calculation. Converting those paths to adjacency entries
     * belongs to the next implementation step, so this method currently returns no entries.
     *
     * @param targets target inventory IDs; empty or null requests every product in the period
     * @param startDate inclusive start of the production period
     * @param endDate inclusive end of the production period
     * @return an analysis result whose adjacency entries are not populated yet
     */
    public AnalysisResults analyser (List<String> targets, LocalDate startDate, LocalDate endDate) {
        attributionWorkflow.trace(startDate, endDate, targets);
        return AnalysisResults.builder()
                .analysisId(UUID.randomUUID())
                .results(List.of())
                .build();
    }
}
