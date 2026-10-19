package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Entry point for the date-scoped attribution chain. */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {

    // Read sold targets -> build edges -> build the CSR graph.
    private final ReadDataHandler readDataHandler;

    /**
     * Builds the CSR graph of products sold in the period and the materials issued to them.
     *
     * @param startDate first day of the period
     * @param endDate last day of the period, inclusive
     * @return the period's CSR graph
     * @throws IllegalArgumentException if a date is missing or the dates are not in order
     */
    public CsrGraph trace(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        TraceContext context = new TraceContext(startDate, endDate);
        readDataHandler.handle(context);

        return context.graph;
    }
}
