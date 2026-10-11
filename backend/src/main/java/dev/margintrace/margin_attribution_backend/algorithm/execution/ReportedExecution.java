package dev.margintrace.margin_attribution_backend.algorithm.execution;

/** Supplies effective execution evidence after initialisation, calculation or explicit fallback. */
public interface ReportedExecution {
    ExecutionReport executionReport();
}
