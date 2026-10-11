package dev.margintrace.margin_attribution_backend.analysis.service;

import dev.margintrace.margin_attribution_backend.algorithm.workflow.*;
import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.RecordPeriodGraphProvider;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.RecordProductionDataProvider;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingPreparation;
import dev.margintrace.margin_attribution_backend.algorithm.execution.BackendRegistry;
import dev.margintrace.margin_attribution_backend.infrastructure.embedding.PythonEmbeddingProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.io.IOException;

/** Warehouse/HTTP adaptation of the independent workflow. Missing context remains caller evidence. */
@Service
public class AnalysisRunService {
    private final AnalysisSourceLoader sources;
    private final EmbeddingPreparation preparation = new EmbeddingPreparation();
    private final String python, model, revision;
    private final long timeout;
    public AnalysisRunService(AnalysisSourceLoader sources,
            @Value("${margin.embedding.python:}") String python,
            @Value("${margin.embedding.model:}") String model,
            @Value("${margin.embedding.revision:}") String revision,
            @Value("${margin.embedding.timeout-seconds:60}") long timeout) {
        this.sources = sources; this.python = python; this.model = model; this.revision = revision; this.timeout = timeout;
    }
    /** Load once, release the transaction, then publish events from the same Java workflow run. */
    public AnalysisOutcome run(AnalysisCommand command, AnalysisProgressListener progress) {
        if (command == null) throw new IllegalArgumentException("Missing analysis command");
        var records = sources.load(command.comparison() != null && command.comparison().completeGraphCoverage());
        return new AnalysisWorkflow(new RecordPeriodGraphProvider(records), new RecordProductionDataProvider(), (tree, node) -> null,
                settings -> {
                    if (settings.mode() == dev.margintrace.margin_attribution_backend.algorithm.embedding.EmbeddingSettings.Mode.SUPPLIED_ONLY)
                        return new dev.margintrace.margin_attribution_backend.infrastructure.embedding.SuppliedEmbeddingProvider(
                                settings.modelId() == null ? model : settings.modelId(), settings.modelRevision() == null ? revision : settings.modelRevision());
                    if (python.isBlank() || (settings.modelId() == null && model.isBlank()) ||
                            (settings.modelRevision() == null && revision.isBlank())) throw new IllegalStateException("Configure margin.embedding.python/model/revision and install the pinned model");
                    Path script = null;
                    try (var resource = getClass().getResourceAsStream("/embedding/sentence_encoder.py")) {
                        script = Files.createTempFile("margin-encoder-", ".py"); Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
                        var provider = new PythonEmbeddingProvider(python, script.toString(), model, revision, settings, timeout);
                        Files.delete(script); return provider;
                    } catch (IOException | RuntimeException error) {
                        if (script != null) try { Files.deleteIfExists(script); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
                        throw new IllegalStateException("Embedding runtime unavailable: " + error.getMessage(), error);
                    }
                }, preparation, new BackendRegistry()).run(command, progress);
    }
}
