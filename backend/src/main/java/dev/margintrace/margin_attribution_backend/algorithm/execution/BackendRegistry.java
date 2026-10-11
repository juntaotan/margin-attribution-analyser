package dev.margintrace.margin_attribution_backend.algorithm.execution;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.*;

/** Currently available, tested Java adapters. Native source alone does not register a binding. */
public final class BackendRegistry {
    public record Selection(FrontierEngine frontier, FgwComparator fgw, ExecutionReport report) {}
    public Selection resolve(String stage, ExecutionPolicy policy) {
        String requested = policy.family();
        String mode = "FRONTIER".equals(stage) ? "SIGNED_LONG_MICRO_UNITS" : "FP64/EXACT_SIMPLEX";
        String missing = "JAVA_CPU".equals(requested) ? null : requested + "_JAVA_BINDING_UNAVAILABLE";
        if (missing != null && !policy.allowCpuFallback()) return new Selection(null, null,
                new ExecutionReport(stage, policy, requested, null, policy.deviceId(), missing, mode, "UNAVAILABLE"));
        return new Selection(new BomUpwardEngine(), new JavaFgwComparator(),
                new ExecutionReport(stage, policy, requested, "JAVA_CPU", null, missing, mode, "AVAILABLE"));
    }
}
