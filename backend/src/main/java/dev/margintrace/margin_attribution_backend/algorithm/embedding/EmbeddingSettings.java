package dev.margintrace.margin_attribution_backend.algorithm.embedding;

/** Inference policy is independent of frontier/FGW execution selection. */
public record EmbeddingSettings(
        Mode mode,
        String modelId,
        String modelRevision,
        String normalization,
        String backend,
        String deviceId,
        boolean allowCpuFallback) {
    public EmbeddingSettings {
        if (mode == null) mode = Mode.GENERATE_MISSING;
        if (normalization == null) normalization = "L2";
        if (backend == null) backend = "CPU";
        if (!java.util.Set.of("CPU", "GPU", "REMOTE").contains(backend))
            throw new IllegalArgumentException("Unsupported inference backend");
        if (!java.util.Set.of("L2", "MODEL_DEFAULT").contains(normalization))
            throw new IllegalArgumentException("Unsupported normalisation policy");
    }

    public enum Mode {
        SUPPLIED_ONLY,
        GENERATE_MISSING
    }

    public static EmbeddingSettings cpu() {
        return new EmbeddingSettings(Mode.GENERATE_MISSING, null, null, "L2", "CPU", null, false);
    }
}
