package dev.margintrace.margin_attribution_backend.investigation;

import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic stand-in for the Python encoder: hashed bag of words, L2-normalised, 384 dimensions.
 * Texts sharing words are similar, which is enough to exercise dense retrieval without a model.
 */
final class FakeEmbeddingProvider implements EmbeddingProvider {
    static final Metadata METADATA = new Metadata("fake-bag-of-words", "1", "MEAN", "L2", "FP32", "CPU", "test", "cpu", null);

    @Override public Metadata metadata() { return METADATA; }

    /** Words plus two special tokens, mirroring a tokenizer that counts [CLS] and [SEP]. */
    @Override public int tokenCount(String text) {
        String trimmed = text.trim();
        return (trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length) + 2;
    }

    @Override public int maximumTokens() { return 512; }

    @Override public Map<String, double[]> encode(List<Input> inputs) {
        Map<String, double[]> vectors = new HashMap<>();
        for (Input input : inputs) {
            double[] vector = new double[384];
            for (String word : input.text().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (word.length() > 2) vector[Math.floorMod(word.hashCode(), 384)] += 1;
            }
            double norm = 0;
            for (double value : vector) norm += value * value;
            norm = norm == 0 ? 1 : Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= norm;
            vectors.put(input.hash(), vector);
        }
        return vectors;
    }
}
