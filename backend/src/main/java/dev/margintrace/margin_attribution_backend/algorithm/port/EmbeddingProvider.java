package dev.margintrace.margin_attribution_backend.algorithm.port;

import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings;
import java.util.List;
import java.util.Map;

/** Model boundary with identified batch responses and owned, explicitly closed inference resources. */
public interface EmbeddingProvider extends AutoCloseable {
    record Metadata(String modelId, String revision, String pooling, String normalization, String precision,
                    String backend, String runtime, String device, String fallbackReason) {
        public String signature() { return modelId + "|" + revision + "|" + pooling + "|" + normalization + "|" + precision; }
    }
    record Input(String hash, String text) {}
    Metadata metadata();
    /** Exact token count including special tokens; no silent truncation is permitted. */
    int tokenCount(String text);
    int maximumTokens();
    Map<String, double[]> encode(List<Input> inputs);
    default void close() {}
}
