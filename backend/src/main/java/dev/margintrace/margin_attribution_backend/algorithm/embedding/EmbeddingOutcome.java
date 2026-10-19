package dev.margintrace.margin_attribution_backend.algorithm.embedding;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFgwAdapter.Embedding;
import dev.margintrace.margin_attribution_backend.algorithm.port.EmbeddingProvider;
import java.util.List;
import java.util.Map;

/** Per-occurrence provenance remains distinct even when canonical vectors are shared. */
public record EmbeddingOutcome(
        Map<String, Embedding> vectors,
        List<Manifest> manifests,
        int supplied,
        int cached,
        int generated,
        int unresolved,
        int uniqueInputs,
        int inferredInputs,
        int submittedInputs,
        double milliseconds,
        EmbeddingProvider.Metadata execution) {
    public record Manifest(
            String occurrenceId,
            String namespace,
            String productionId,
            int index,
            String hash,
            String sourceHash,
            String canonicalInput,
            MaterialContext context,
            String signature,
            String resolution,
            List<String> statuses) {}

    public record Supplied(String inputHash, String signature, double[] values) {
        public Supplied {
            if (values != null) values = values.clone();
        }
    }
}
