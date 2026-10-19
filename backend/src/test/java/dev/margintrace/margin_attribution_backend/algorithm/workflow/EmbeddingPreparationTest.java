package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import static dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomFrontierReconciler;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.RecordPeriodGraphProvider;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.*;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EmbeddingPreparationTest {
    static List<ProductionTree> trees() {
        var cmd = command();
        var graphs = new RecordPeriodGraphProvider(records());
        var a = graphs.load(cmd.actualStartDate(), cmd.actualEndDate());
        var c = graphs.load(cmd.comparableStartDate(), cmd.comparableEndDate());
        return new RecordProductionDataProvider()
                .load(
                        a,
                        c,
                        new BomFrontierReconciler()
                                .reconcile(a.graph(), c.graph(), cmd.leafThreshold(), cmd.stopThreshold()),
                        cmd.comparison().evidence())
                .trees();
    }

    static EmbeddingOutcome prepare(
            EmbeddingPreparation p,
            List<ProductionTree> trees,
            EmbeddingContextProvider contexts,
            EmbeddingProvider provider,
            EmbeddingSettings settings,
            Map<String, EmbeddingOutcome.Supplied> supplied) {
        return p.prepare(trees, contexts, provider, settings, supplied, () -> false);
    }

    static MaterialContext.Evidence change(
            MaterialContext.Evidence e, List<String> suppliers, String cost, boolean conflicting) {
        return new MaterialContext.Evidence(
                e.description(),
                e.sourceKind(),
                suppliers,
                cost == null ? null : d(cost),
                e.currency(),
                e.unit(),
                e.costScope(),
                e.valuationBasis(),
                e.references(),
                conflicting);
    }

    @Test
    void E01_E02_allFieldsAndAlignedFinalNeighbours() {
        var out = prepare(
                new EmbeddingPreparation(),
                trees(),
                AnalysisFixtures::context,
                new RecordingProvider(),
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(14, out.manifests().size());
        assertEquals(14, out.generated());
        assertEquals(0, out.unresolved());
        for (var manifest : out.manifests()) {
            for (String field : List.of(
                    "code=",
                    "name=",
                    "specification=",
                    "supplier=",
                    "category=",
                    "unit cost=",
                    "upstream codes=",
                    "upstream names=",
                    "upstream specifications=",
                    "downstream codes=",
                    "downstream names=",
                    "downstream specifications="))
                assertTrue(manifest.canonicalInput().contains(field), field);
            assertFalse(manifest.context().evidence().references().isEmpty());
            assertEquals(384, out.vectors().get(manifest.occurrenceId()).values().length);
            assertFalse(manifest.canonicalInput().contains("P0"));
            assertFalse(manifest.canonicalInput().contains("P1"));
            assertEquals("MEAN", out.execution().pooling());
            if (manifest.context().code().equals("A")) {
                assertEquals("INTERNAL", manifest.context().evidence().sourceKind());
                assertEquals(2, manifest.context().upstream().size());
                assertTrue(manifest.context().downstream().isEmpty());
            }
            if (manifest.context().code().startsWith("R")) {
                assertTrue(manifest.context().upstream().isEmpty());
                assertEquals(1, manifest.context().downstream().size());
            }
        }
    }

    @Test
    void E03_supplierMultiplicityPermutationAndSemanticChanges() {
        var trees = trees();
        var prep = new EmbeddingPreparation();
        var provider = new RecordingProvider();
        EmbeddingContextProvider two = (t, n) -> {
            var e = context(t, n);
            return n.materialId().equals("R1") ? change(e, List.of("second", "first"), "5", false) : e;
        };
        EmbeddingContextProvider reversed = (t, n) -> {
            var e = context(t, n);
            return n.materialId().equals("R1") ? change(e, List.of("first", "second"), "5.0", false) : e;
        };
        var first = prepare(prep, trees, two, provider, EmbeddingSettings.cpu(), Map.of());
        var next = prepare(prep, trees, reversed, provider, EmbeddingSettings.cpu(), Map.of());
        assertEquals(
                first.manifests().stream().map(EmbeddingOutcome.Manifest::hash).toList(),
                next.manifests().stream().map(EmbeddingOutcome.Manifest::hash).toList());
        assertEquals(14, next.cached());
        var altered = prepare(
                prep,
                trees,
                (t, n) -> {
                    var e = two.evidence(t, n);
                    return n.materialId().equals("R1") ? change(e, List.of("third"), "6", false) : e;
                },
                provider,
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(2, altered.generated());
        assertEquals(12, altered.cached());
    }

    @Test
    void E04_missingConflictingAndUnverifiedInputs() {
        var p = new EmbeddingPreparation();
        var provider = new RecordingProvider();
        var missing = prepare(
                p,
                trees(),
                (t, n) -> change(context(t, n), List.of(), null, false),
                provider,
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(14, missing.unresolved());
        assertTrue(provider.seen.isEmpty());
        assertTrue(missing.manifests().stream()
                .allMatch(m -> m.statuses().stream().anyMatch(s -> s.contains("unitCost"))));
        var conflicts = prepare(
                p,
                trees(),
                (t, n) -> change(context(t, n), context(t, n).suppliers(), "1", true),
                provider,
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(14, conflicts.unresolved());
        assertTrue(conflicts.manifests().getFirst().statuses().contains("CONFLICTING_EMBEDDING_CONTEXT"));
        var descriptions = prepare(
                p,
                trees(),
                (t, n) -> new MaterialContext.Evidence(
                        null,
                        "INTERNAL",
                        List.of(),
                        d("1"),
                        "NZD",
                        unit(n.materialId()),
                        "manufacturing",
                        "explicit",
                        List.of(),
                        false),
                provider,
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(14, descriptions.unresolved());
    }

    @Test
    void E05_E06_deduplicationCacheInvalidationNamespaceAndReorderedResponses() {
        var p = new EmbeddingPreparation();
        var trees = trees();
        var provider = new RecordingProvider();
        var first = prepare(p, trees, AnalysisFixtures::context, provider, EmbeddingSettings.cpu(), Map.of());
        assertEquals(first.uniqueInputs(), provider.seen.size());
        assertTrue(first.uniqueInputs() < 14);
        assertEquals(first.uniqueInputs(), first.inferredInputs());
        var next = prepare(p, trees, AnalysisFixtures::context, provider, EmbeddingSettings.cpu(), Map.of());
        assertEquals(14, next.cached());
        assertEquals(0, next.inferredInputs());
        var different = trees.stream()
                .map(t -> AnalysisWorkflowTest.copyIdentity(t, t.productionId(), "other-namespace", t.productId()))
                .toList();
        assertEquals(
                14,
                prepare(p, different, AnalysisFixtures::context, provider, EmbeddingSettings.cpu(), Map.of())
                        .generated());
        var revision = new RecordingProvider() {
            @Override
            public Metadata metadata() {
                return new Metadata(
                        "recording-test", "other-revision", "MEAN", "L2", "FP32", "CPU", "TEST_ONLY", "cpu", null);
            }
        };
        assertEquals(
                14,
                prepare(p, trees, AnalysisFixtures::context, revision, EmbeddingSettings.cpu(), Map.of())
                        .generated());
        var supplied = new HashMap<String, EmbeddingOutcome.Supplied>();
        for (var m : first.manifests())
            supplied.put(
                    m.occurrenceId(),
                    new EmbeddingOutcome.Supplied(
                            m.hash(),
                            m.signature(),
                            first.vectors().get(m.occurrenceId()).values()));
        var suppliedProvider = new RecordingProvider();
        var only = new EmbeddingSettings(EmbeddingSettings.Mode.SUPPLIED_ONLY, null, null, "L2", "CPU", null, false);
        var output =
                prepare(new EmbeddingPreparation(), trees, AnalysisFixtures::context, suppliedProvider, only, supplied);
        assertEquals(14, output.supplied());
        assertTrue(suppliedProvider.seen.isEmpty());
        var id = first.manifests().getFirst().occurrenceId();
        supplied.put(
                id, new EmbeddingOutcome.Supplied("wrong", first.execution().signature(), new double[384]));
        var invalid =
                prepare(new EmbeddingPreparation(), trees, AnalysisFixtures::context, suppliedProvider, only, supplied);
        assertEquals(1, invalid.unresolved());
        assertTrue(invalid.manifests().getFirst().statuses().contains("UNVERIFIED_EMBEDDING_PROVENANCE"));
    }

    @Test
    void E07_invalidModelOutputsAndVersions() {
        for (int kind = 0; kind < 4; kind++) {
            final int variant = kind;
            var bad = new RecordingProvider() {
                @Override
                public Map<String, double[]> encode(List<Input> inputs) {
                    var values = new HashMap<String, double[]>();
                    for (var i : inputs) {
                        var v = new double[variant == 0 ? 383 : 384];
                        if (variant == 1) v[0] = Double.NaN;
                        if (variant == 2) v[0] = Double.POSITIVE_INFINITY;
                        values.put(i.hash(), v);
                    }
                    return values;
                }
            };
            var result = prepare(
                    new EmbeddingPreparation(),
                    trees(),
                    AnalysisFixtures::context,
                    bad,
                    EmbeddingSettings.cpu(),
                    Map.of());
            assertEquals(14, result.unresolved());
            assertTrue(result.vectors().isEmpty());
        }
        var wrong = new EmbeddingSettings(
                EmbeddingSettings.Mode.GENERATE_MISSING, "different-model", null, "L2", "CPU", null, false);
        assertEquals(
                14,
                prepare(
                                new EmbeddingPreparation(),
                                trees(),
                                AnalysisFixtures::context,
                                new RecordingProvider(),
                                wrong,
                                Map.of())
                        .unresolved());
    }

    @Test
    void E08_overlengthProviderFailurePartialTimeoutAndCancellation() {
        var longInput = new RecordingProvider() {
            @Override
            public int maximumTokens() {
                return 1;
            }
        };
        var tooLong = prepare(
                new EmbeddingPreparation(),
                trees(),
                AnalysisFixtures::context,
                longInput,
                EmbeddingSettings.cpu(),
                Map.of());
        assertEquals(14, tooLong.unresolved());
        assertTrue(longInput.seen.isEmpty());
        assertTrue(tooLong.manifests().getFirst().statuses().contains("EMBEDDING_INPUT_TOO_LONG"));
        var failure = new RecordingProvider() {
            @Override
            public Map<String, double[]> encode(List<Input> inputs) {
                throw new IllegalStateException("EMBEDDING_TIMEOUT");
            }
        };
        assertEquals(
                14,
                prepare(
                                new EmbeddingPreparation(),
                                trees(),
                                AnalysisFixtures::context,
                                failure,
                                EmbeddingSettings.cpu(),
                                Map.of())
                        .unresolved());
        var partial = new RecordingProvider() {
            @Override
            public Map<String, double[]> encode(List<Input> inputs) {
                var result = new HashMap<>(super.encode(inputs));
                result.remove(inputs.getFirst().hash());
                return result;
            }
        };
        var part = prepare(
                new EmbeddingPreparation(),
                trees(),
                AnalysisFixtures::context,
                partial,
                EmbeddingSettings.cpu(),
                Map.of());
        assertTrue(part.unresolved() > 0);
        assertTrue(part.generated() > 0);
        var cancelled = new RecordingProvider();
        var out = new EmbeddingPreparation()
                .prepare(trees(), AnalysisFixtures::context, cancelled, EmbeddingSettings.cpu(), Map.of(), () -> true);
        assertEquals(14, out.unresolved());
        assertTrue(cancelled.seen.isEmpty());
        var unknown = new RecordingProvider() {
            @Override
            public Map<String, double[]> encode(List<Input> inputs) {
                var result = new HashMap<>(super.encode(inputs));
                result.put("unknown", new double[384]);
                return result;
            }
        };
        assertEquals(
                14,
                prepare(
                                new EmbeddingPreparation(),
                                trees(),
                                AnalysisFixtures::context,
                                unknown,
                                EmbeddingSettings.cpu(),
                                Map.of())
                        .unresolved());
    }

    @Test
    void E09_E10_reuseAndTruthfulInferenceBackend() {
        var provider = new RecordingProvider();
        var pipeline = AnalysisWorkflowTest.workflow(provider);
        var first = pipeline.run(command());
        int calls = provider.seen.size();
        var second = pipeline.run(command());
        assertEquals(calls, provider.seen.size());
        assertEquals(14, second.embeddings().cached());
        assertEquals(1, first.executed());
        var gpu = new EmbeddingSettings(EmbeddingSettings.Mode.GENERATE_MISSING, null, null, "L2", "GPU", "0", false);
        assertEquals(
                14,
                prepare(
                                new EmbeddingPreparation(),
                                trees(),
                                AnalysisFixtures::context,
                                new RecordingProvider(),
                                gpu,
                                Map.of())
                        .unresolved());
        var fallback = new RecordingProvider() {
            @Override
            public Metadata metadata() {
                return new Metadata(
                        "recording-test",
                        "fixture-revision",
                        "MEAN",
                        "L2",
                        "FP32",
                        "CPU",
                        "TEST_ONLY",
                        "cpu",
                        "GPU_UNAVAILABLE");
            }
        };
        var permitted = new EmbeddingSettings(
                gpu.mode(),
                gpu.modelId(),
                gpu.modelRevision(),
                gpu.normalization(),
                gpu.backend(),
                gpu.deviceId(),
                true);
        var result =
                prepare(new EmbeddingPreparation(), trees(), AnalysisFixtures::context, fallback, permitted, Map.of());
        assertEquals(14, result.generated());
        assertEquals("GPU_UNAVAILABLE", result.execution().fallbackReason());
    }
}
