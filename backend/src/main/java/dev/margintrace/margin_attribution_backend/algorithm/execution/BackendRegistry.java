package dev.margintrace.margin_attribution_backend.algorithm.execution;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.*;
import dev.margintrace.margin_attribution_backend.infrastructure.acceleration.NativeAcceleration;
import dev.margintrace.margin_attribution_backend.infrastructure.acceleration.NativeExecutionException;

/** Selects only the caller-requested family, preserving explicit CPU fallback and stage evidence. */
public final class BackendRegistry {
    public record Selection(FrontierEngine frontier, FgwComparator fgw, ExecutionReport report)
            implements AutoCloseable {
        /** Effective evidence may change after lazy device initialisation or authorised fallback. */
        public ExecutionReport currentReport() {
            if (frontier instanceof ReportedExecution engine) return engine.executionReport();
            if (fgw instanceof ReportedExecution engine) return engine.executionReport();
            return report;
        }

        public void close() {
            if (fgw != null) fgw.close();
        }
    }
    /** Returns one stage adapter or an explicit unavailable result; never switches GPU family. */
    public Selection resolve(String stage, ExecutionPolicy policy) {
        if (!stage.equals("FRONTIER") && !stage.equals("FGW"))
            throw new IllegalArgumentException("Unknown execution stage: " + stage);
        String requested = policy.family();
        String mode = stage.equals("FRONTIER") ? "SIGNED_LONG_MICRO_UNITS" : "FP64/EXACT_SIMPLEX";
        String missing = null;
        if (policy.useGpu()) {
            try {
                return NativeAcceleration.resolve(stage, policy);
            } catch (NativeExecutionException error) {
                missing = error.getMessage();
            }
        }
        if (missing != null && !policy.allowCpuFallback())
            return new Selection(
                    null,
                    null,
                    new ExecutionReport(
                            stage, policy, requested, null, policy.deviceId(), missing, mode, "UNAVAILABLE"));
        return new Selection(
                stage.equals("FRONTIER") ? new BomUpwardEngine() : null,
                stage.equals("FGW") ? new JavaFgwComparator() : null,
                new ExecutionReport(stage, policy, requested, "JAVA_CPU", null, missing, mode, "AVAILABLE"));
    }
}
