package dev.margintrace.margin_attribution_backend.infrastructure.embedding;

import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider;
import java.util.List;
import java.util.Map;

/** Provenance-only resolver for the pinned MEAN/L2/FP32 profile; never loads a model or performs inference. */
public final class SuppliedEmbeddingProvider implements EmbeddingProvider {
    private final Metadata metadata;
    public SuppliedEmbeddingProvider(String model, String revision) {
        if (model == null || model.isBlank() || revision == null || !revision.matches("[a-f0-9]{40}"))
            throw new IllegalArgumentException("Supplied embeddings require a configured model and immutable revision");
        metadata = new Metadata(model, revision, "MEAN", "L2", "FP32", "NONE", "SUPPLIED_ONLY", null, null);
    }
    public Metadata metadata() { return metadata; }
    public int tokenCount(String text) { throw new IllegalStateException("SUPPLIED_ONLY performs no tokenisation"); }
    public int maximumTokens() { return 0; }
    public Map<String, double[]> encode(List<Input> inputs) { throw new IllegalStateException("SUPPLIED_ONLY performs no inference"); }
}
