package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import dev.margintrace.margin_attribution_backend.algorithm.model.CsrResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Entry point for the date-scoped attribution chain. */
@Component
@RequiredArgsConstructor
public class AttributionWorkflow {
    private final ReadDataHandler firstHandler;

    public CsrResult trace(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        TraceContext context = new TraceContext(startDate, endDate);
        firstHandler.handle(context);
        return context.result;
    }
}
