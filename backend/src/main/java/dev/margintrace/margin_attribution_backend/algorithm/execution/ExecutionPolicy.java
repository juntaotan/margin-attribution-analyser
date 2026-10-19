package dev.margintrace.margin_attribution_backend.algorithm.execution;

/** Caller-selected engine family; vendor detection belongs to the caller. */
public record ExecutionPolicy(boolean useGpu, Boolean isNvidia, String deviceId, boolean allowCpuFallback) {
    public ExecutionPolicy {
        if (useGpu && isNvidia == null) throw new IllegalArgumentException("isNvidia is required for GPU execution");
    }

    /**
     * @return a policy that runs on the Java CPU implementation
     */
    public static ExecutionPolicy cpu() {
        return new ExecutionPolicy(false, null, null, false);
    }

    /**
     * @return {@code JAVA_CPU}, {@code CUDA} or {@code OPENCL}
     */
    public String family() {
        return !useGpu ? "JAVA_CPU" : isNvidia ? "CUDA" : "OPENCL";
    }
}
