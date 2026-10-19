package dev.margintrace.margin_attribution_backend.algorithm.port;

import java.util.List;
import java.util.Map;

/** Model boundary with identified batch responses and owned, explicitly closed inference resources. */
public interface EmbeddingProvider extends AutoCloseable {
    /**
     * Identity of the embedding model and how it was run. The {@link #signature()} identifies vectors that can be
     * compared with each other.
     */
    record Metadata(
            String modelId,
            String revision,
            String pooling,
            String normalization,
            String precision,
            String backend,
            String runtime,
            String device,
            String fallbackReason) {
        /**
         * @return the model, revision, pooling, normalisation and precision joined by {@code |}
         */
        public String signature() {
            return modelId + "|" + revision + "|" + pooling + "|" + normalization + "|" + precision;
        }
    }

    /**
     * Text to embed, identified by a caller-chosen key such as its hash.
     */
    record Input(String hash, String text) {}

    /**
     * @return the model identity and runtime of this provider
     */
    Metadata metadata();
    /** Exact token count including special tokens; no silent truncation is permitted. */
    int tokenCount(String text);

    /**
     * @return the largest input, in tokens, the model accepts without truncation
     */
    int maximumTokens();

    /**
     * @param inputs the texts to embed
     * @return one vector per input, keyed by the input's key
     * @throws RuntimeException if inference fails or is cancelled
     */
    Map<String, double[]> encode(List<Input> inputs);

    default void close() {}
}
