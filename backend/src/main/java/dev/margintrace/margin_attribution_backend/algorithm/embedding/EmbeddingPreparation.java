package dev.margintrace.margin_attribution_backend.algorithm.embedding;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFgwAdapter.Embedding;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Extracts final-CSR contexts, validates provenance, deduplicates and resolves bounded batches.
 * The bounded cache is scoped by namespace, complete input and model signature. It stores only
 * successfully validated vectors. The calling workflow owns and closes its inference provider.
 */
public final class EmbeddingPreparation {
    private final Map<String, double[]> cache = Collections.synchronizedMap(new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> entry) {
            return size() > 1024;
        }
    });
    private static final Comparator<MaterialContext.Neighbour> ORDER = Comparator.comparing(
                    MaterialContext.Neighbour::code)
            .thenComparing(MaterialContext.Neighbour::name)
            .thenComparing(MaterialContext.Neighbour::specification)
            .thenComparing(MaterialContext.Neighbour::occurrenceId);

    /** Prepare every occurrence once; rejected inputs retain manifests and never become zero vectors. */
    public EmbeddingOutcome prepare(
            List<ProductionTree> trees,
            EmbeddingContextProvider contextProvider,
            EmbeddingProvider provider,
            EmbeddingSettings settings,
            Map<String, EmbeddingOutcome.Supplied> supplied,
            BooleanSupplier cancelled) {
        long started = System.nanoTime();
        var metadata = provider == null ? null : provider.metadata();
        String signature = metadata == null ? null : metadata.signature();
        var vectors = new LinkedHashMap<String, Embedding>();
        var pending = new LinkedHashMap<String, EmbeddingProvider.Input>();
        var manifests = new ArrayList<EmbeddingOutcome.Manifest>();
        var resolutions = new HashMap<String, String>();
        var errors = new HashMap<String, List<String>>();
        var keys = new HashMap<String, String>();
        int suppliedCount = 0, cachedCount = 0, generatedCount = 0, inferred = 0, submittedInputs = 0;
        Set<String> hashes = new HashSet<>();
        for (var tree : trees) {
            var evidence = new HashMap<Integer, MaterialContext.Evidence>();
            var contextFailures = new HashMap<Integer, String>();
            for (var node : tree.nodes()) {
                try {
                    evidence.put(node.index(), contextProvider.evidence(tree, node));
                } catch (RuntimeException error) {
                    contextFailures.put(node.index(), "EMBEDDING_CONTEXT_PROVIDER_FAILURE:" + error.getMessage());
                }
            }
            for (var node : tree.nodes()) {
                List<String> status = new ArrayList<>(validate(evidence.get(node.index()), node));
                if (contextFailures.containsKey(node.index())) status.add(contextFailures.get(node.index()));
                var upstream = new ArrayList<MaterialContext.Neighbour>();
                var downstream = new ArrayList<MaterialContext.Neighbour>();
                for (var edge : tree.edges()) {
                    if (edge.receivingIndex() == node.index())
                        neighbour(tree, evidence, edge.materialIndex(), upstream, status);
                    if (edge.materialIndex() == node.index())
                        neighbour(tree, evidence, edge.receivingIndex(), downstream, status);
                }
                upstream.sort(ORDER);
                downstream.sort(ORDER);
                MaterialContext context = new MaterialContext(
                        node.materialId(), evidence.get(node.index()), List.copyOf(upstream), List.copyOf(downstream));
                String text = status.isEmpty() ? context.canonical() : null;
                String hash = text == null ? null : hash(text);
                String key = tree.namespace() + "|MATERIAL_CONTEXT_V1/1|" + hash + "|" + signature;
                if (hash != null) {
                    hashes.add(tree.namespace() + "|" + hash);
                    keys.put(node.occurrenceId(), key);
                }
                if (status.isEmpty() && metadata == null) status.add("EMBEDDING_INPUT_REQUIRED");
                if (status.isEmpty()
                        && ((settings.modelId() != null && !settings.modelId().equals(metadata.modelId()))
                                || (settings.modelRevision() != null
                                        && !settings.modelRevision().equals(metadata.revision()))
                                || (!"MODEL_DEFAULT".equals(settings.normalization())
                                        && !Objects.equals(settings.normalization(), metadata.normalization()))))
                    status.add("INCOMPATIBLE_EMBEDDING_PROVENANCE");
                if (status.isEmpty()
                        && settings.mode() != EmbeddingSettings.Mode.SUPPLIED_ONLY
                        && !settings.backend().equals(metadata.backend())
                        && !(settings.allowCpuFallback()
                                && "CPU".equals(metadata.backend())
                                && metadata.fallbackReason() != null)) status.add("EMBEDDING_BACKEND_UNAVAILABLE");
                if (status.isEmpty() && cancelled.getAsBoolean()) status.add("CANCELLED");
                var given = supplied.get(node.occurrenceId());
                String resolution = "UNRESOLVED";
                if (status.isEmpty() && given != null) {
                    if (!Objects.equals(given.inputHash(), hash) || !Objects.equals(given.signature(), signature))
                        status.add("UNVERIFIED_EMBEDDING_PROVENANCE");
                    else {
                        status.addAll(validateVector(given.values()));
                        if (status.isEmpty()) {
                            vectors.put(node.occurrenceId(), new Embedding(signature, given.values()));
                            resolution = "SUPPLIED";
                            suppliedCount++;
                        }
                    }
                } else if (status.isEmpty()) {
                    double[] hit = cache.get(key);
                    if (hit != null) {
                        vectors.put(node.occurrenceId(), new Embedding(signature, hit));
                        resolution = "CACHE";
                        cachedCount++;
                    } else if (settings.mode() == EmbeddingSettings.Mode.SUPPLIED_ONLY)
                        status.add("MISSING_EMBEDDINGS");
                    else {
                        try {
                            if (provider.tokenCount(text) > provider.maximumTokens())
                                status.add("EMBEDDING_INPUT_TOO_LONG");
                            else
                                pending.putIfAbsent(
                                        tree.namespace() + "|" + hash,
                                        new EmbeddingProvider.Input(tree.namespace() + "|" + hash, text));
                        } catch (RuntimeException failure) {
                            status.add("EMBEDDING_PROVIDER_FAILURE:" + failure.getMessage());
                        }
                    }
                }
                resolutions.put(node.occurrenceId(), resolution);
                errors.put(node.occurrenceId(), status);
                manifests.add(new EmbeddingOutcome.Manifest(
                        node.occurrenceId(),
                        tree.namespace(),
                        tree.productionId(),
                        node.index(),
                        hash,
                        hash(String.valueOf(evidence.get(node.index())) + node.issueLineIds()),
                        text,
                        context,
                        signature,
                        resolution,
                        List.copyOf(status)));
            }
        }
        Map<String, double[]> generated = new HashMap<>();
        Set<String> reusedSupplied = new HashSet<>();
        for (var manifest : manifests) {
            if ("SUPPLIED".equals(resolutions.get(manifest.occurrenceId()))) {
                String inputId = manifest.namespace() + "|" + manifest.hash();
                generated.put(inputId, vectors.get(manifest.occurrenceId()).values());
                reusedSupplied.add(inputId);
                pending.remove(inputId);
            }
        }
        Map<String, String> failures = new HashMap<>();
        var inputs = new ArrayList<>(pending.values());
        for (int from = 0; from < inputs.size(); from += 16) {
            var batch = inputs.subList(from, Math.min(from + 16, inputs.size()));
            if (cancelled.getAsBoolean()) {
                batch.forEach(i -> failures.put(i.hash(), "CANCELLED"));
                continue;
            }
            try {
                submittedInputs += batch.size();
                var response = provider.encode(List.copyOf(batch));
                if (response.keySet().stream()
                        .anyMatch(h -> batch.stream().noneMatch(i -> i.hash().equals(h))))
                    throw new IllegalArgumentException("Unsubmitted response identity");
                for (var input : batch) {
                    var vector = response.get(input.hash());
                    var status = validateVector(vector);
                    if (status.isEmpty()) {
                        generated.put(input.hash(), vector.clone());
                        inferred++;
                    } else failures.put(input.hash(), String.join(",", status));
                }
            } catch (RuntimeException failure) {
                batch.forEach(i -> failures.put(
                        i.hash(),
                        failure instanceof java.util.concurrent.CancellationException
                                ? "CANCELLED"
                                : "EMBEDDING_PROVIDER_FAILURE:" + failure.getMessage()));
            }
        }
        var finalManifests = new ArrayList<EmbeddingOutcome.Manifest>();
        for (var manifest : manifests) {
            String id = manifest.occurrenceId();
            var status = errors.get(id);
            String inputId = manifest.namespace() + "|" + manifest.hash();
            if (status.equals(List.of("MISSING_EMBEDDINGS")) && reusedSupplied.contains(inputId)) status.clear();
            if (status.isEmpty() && !vectors.containsKey(id)) {
                var vector = generated.get(inputId);
                if (vector != null) {
                    vectors.put(id, new Embedding(signature, vector));
                    cache.put(keys.get(id), vector.clone());
                    if (reusedSupplied.contains(inputId)) {
                        resolutions.put(id, "SUPPLIED_REUSE");
                        suppliedCount++;
                    } else {
                        resolutions.put(id, "GENERATED");
                        generatedCount++;
                    }
                } else status.add(failures.getOrDefault(inputId, "MISSING_EMBEDDINGS"));
            }
            finalManifests.add(new EmbeddingOutcome.Manifest(
                    id,
                    manifest.namespace(),
                    manifest.productionId(),
                    manifest.index(),
                    manifest.hash(),
                    manifest.sourceHash(),
                    manifest.canonicalInput(),
                    manifest.context(),
                    signature,
                    resolutions.get(id),
                    List.copyOf(status)));
        }
        return new EmbeddingOutcome(
                Map.copyOf(vectors),
                List.copyOf(finalManifests),
                suppliedCount,
                cachedCount,
                generatedCount,
                manifests.size() - vectors.size(),
                hashes.size(),
                inferred,
                submittedInputs,
                (System.nanoTime() - started) / 1e6,
                metadata);
    }

    private static void neighbour(
            ProductionTree tree,
            Map<Integer, MaterialContext.Evidence> evidence,
            int index,
            List<MaterialContext.Neighbour> nodes,
            List<String> errors) {
        var node = tree.nodes().get(index);
        var data = evidence.get(index);
        if (data == null
                || data.description() == null
                || blank(data.description().name())
                || blank(data.description().specification()))
            errors.add("MISSING_EMBEDDING_CONTEXT:neighbour:" + node.occurrenceId());
        else
            nodes.add(new MaterialContext.Neighbour(
                    node.occurrenceId(),
                    node.materialId(),
                    data.description().name(),
                    data.description().specification()));
    }

    private static List<String> validate(MaterialContext.Evidence e, ProductionTree.Occurrence n) {
        var errors = new ArrayList<String>();
        if (e == null) return List.of("MISSING_EMBEDDING_CONTEXT:evidence");
        if (e.conflicting()) errors.add("CONFLICTING_EMBEDDING_CONTEXT");
        if (e.description() == null
                || blank(e.description().name())
                || blank(e.description().specification())
                || blank(e.description().category())) errors.add("MISSING_EMBEDDING_CONTEXT:description");
        if (e.description() != null
                && (e.description().references() == null
                        || e.description().references().isEmpty()))
            errors.add("MISSING_EMBEDDING_CONTEXT:description.references");
        if (!Set.of("INTERNAL", "PURCHASED").contains(String.valueOf(e.sourceKind()))
                || e.suppliers() == null
                || ("PURCHASED".equals(e.sourceKind()) && e.suppliers().isEmpty())
                || (e.suppliers() != null && e.suppliers().stream().anyMatch(EmbeddingPreparation::blank)))
            errors.add("MISSING_EMBEDDING_CONTEXT:supplier");
        if (e.unitCost() == null || e.unitCost().signum() < 0 || blank(e.valuationBasis()))
            errors.add("MISSING_EMBEDDING_CONTEXT:unitCost");
        if (blank(e.currency())
                || blank(e.unit())
                || blank(e.costScope())
                || e.references() == null
                || e.references().isEmpty()) errors.add("MISSING_EMBEDDING_CONTEXT:provenance");
        if ((n.unit() != null && !Objects.equals(n.unit(), e.unit()))
                || (n.currency() != null && !Objects.equals(n.currency(), e.currency()))
                || (n.costScope() != null && !Objects.equals(n.costScope(), e.costScope())))
            errors.add("CONFLICTING_EMBEDDING_CONTEXT:scope");
        return errors;
    }

    public static List<String> validateVector(double[] vector) {
        if (vector == null) return List.of("MISSING_EMBEDDINGS");
        if (vector.length != 384) return List.of("INVALID_EMBEDDING_DIMENSION");
        double norm = 0;
        for (double value : vector) {
            if (!Double.isFinite(value)) return List.of("NONFINITE_EMBEDDING");
            norm += value * value;
        }
        return norm > 0 && Double.isFinite(norm) ? List.of() : List.of("ZERO_OR_INVALID_EMBEDDING_NORM");
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
