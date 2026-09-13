package dev.margintrace.margin_attribution_backend.algorithm;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Entry point for reading material usage, building a graph and tracing a target upstream.
 * Quantities remain the original recorded quantities; no quantity or cost allocation is performed.
 */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {

    /**
     * @param startDate
     * @param endDate
     * @param targets
     * @return the graph and source-to-target paths whose indexes refer to that graph's nodes
     * @throws IllegalArgumentException if the range is inverted, the target is blank, absent,
     *                                  or matches multiple nodes with different quantities or costs
     */
    public CsrResult trace(LocalDate startDate, LocalDate endDate, List<String> targets) {
        // TODO: generate csrGraph and return CsrResult as its result
        throw new UnsupportedOperationException("Implement me!");
    }


}
