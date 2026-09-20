package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Entry point for the date-scoped attribution chain. */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {

    // This workflow uses responsibilty chain model: 
    // Read data -> Connect nodes -> Build Csr Graph (offset and succ)
    private final ReadDataHandler readDataHandler;

    public CsrResult trace(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        TraceContext context = new TraceContext(startDate, endDate);
        readDataHandler.handle(context);

        return context.result;
    }
}
