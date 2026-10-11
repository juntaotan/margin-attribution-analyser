package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.util.*;
import java.util.function.*;

/**
 * Synchronous reusable analysis over immutable, explicitly loaded sources. Java orchestration
 * retains discovery/selection/context evidence through eligibility and numerical failures.
 * Pair execution is serial and bounded to one live solve; no fixed baseline replaces all-pairs.
 * Provider resources close before return, including partial and cancelled runs.
 */
public final class AnalysisWorkflow {
    private final PeriodGraphProvider graphs;
    private final ProductionDataProvider productions;
    private final EmbeddingContextProvider contexts;
    private final Function<EmbeddingSettings, EmbeddingProvider> providers;
    private final EmbeddingPreparation preparation;
    private final BackendRegistry backends;

    public AnalysisWorkflow(PeriodGraphProvider graphs, ProductionDataProvider productions, EmbeddingContextProvider contexts,
                            Function<EmbeddingSettings, EmbeddingProvider> providers) {
        this(graphs, productions, contexts, providers, new EmbeddingPreparation(), new BackendRegistry());
    }
    public AnalysisWorkflow(PeriodGraphProvider graphs, ProductionDataProvider productions, EmbeddingContextProvider contexts,
                            Function<EmbeddingSettings, EmbeddingProvider> providers, EmbeddingPreparation preparation, BackendRegistry backends) {
        this.graphs = graphs; this.productions = productions; this.contexts = contexts;
        this.providers = providers; this.preparation = preparation; this.backends = backends;
    }
    public AnalysisOutcome run(AnalysisCommand command) { return run(command, event -> {}); }
    public AnalysisOutcome run(AnalysisCommand command, AnalysisProgressListener progress) {
        return run(command, progress, () -> Thread.currentThread().isInterrupted());
    }

    /** Runs every stage once, publishing increasing event sequences from this same outcome. */
    public AnalysisOutcome run(AnalysisCommand command, AnalysisProgressListener progress, BooleanSupplier cancelled) {
        if (command == null) throw new IllegalArgumentException("Missing analysis command");
        dates(command.actualStartDate(), command.actualEndDate()); dates(command.comparableStartDate(), command.comparableEndDate());
        BomGraphPreparer.validateThreshold(command.leafThreshold()); BomGraphPreparer.validateThreshold(command.stopThreshold());
        ExecutionPolicy policy = command.execution() == null ? ExecutionPolicy.cpu() : command.execution();
        var settings = command.embedding() == null ? EmbeddingSettings.cpu() : command.embedding();
        if (command.comparison() != null && command.comparison().config() != null) FgwEngine.validateConfig(command.comparison().config());
        UUID id = UUID.randomUUID(); long[] sequence = {0};
        BiConsumer<String, Object> emit = (type, data) -> progress.accept(new AnalysisProgressListener.Event(id, ++sequence[0], type, data));
        var reports = new ArrayList<ExecutionReport>(); var diagnostics = new ArrayList<String>();
        var actual = graphs.load(command.actualStartDate(), command.actualEndDate());
        var comparable = graphs.load(command.comparableStartDate(), command.comparableEndDate());
        reports.add(new ExecutionReport("CSR", policy, "JAVA_CPU", "JAVA_CPU", null, null, "DECIMAL", "EXECUTED"));
        diagnostics.addAll(actual.diagnostics()); diagnostics.addAll(comparable.diagnostics());
        var comparableIds = new HashSet<String>();
        Arrays.stream(comparable.graph().nodes()).forEach(n -> comparableIds.add(n.inventoryId()));
        var actualIds = new HashSet<String>();
        Arrays.stream(actual.graph().nodes()).forEach(n -> {
            actualIds.add(n.inventoryId());
            if (!comparableIds.contains(n.inventoryId())) diagnostics.add("ACTUAL_ONLY:" + n.inventoryId());
            else if (n.cost() == null || Arrays.stream(comparable.graph().nodes()).anyMatch(c -> c.inventoryId().equals(n.inventoryId()) && c.cost() == null))
                diagnostics.add("NONCOMPARABLE_COST:" + n.inventoryId());
        });
        comparableIds.stream().filter(c -> !actualIds.contains(c)).sorted().forEach(c -> diagnostics.add("COMPARABLE_ONLY:" + c));
        emit.accept("graph", List.of(actual, comparable));
        var frontier = backends.resolve("FRONTIER", policy); reports.add(frontier.report());
        var paths = List.<dev.margintrace.margin_attribution_backend.algorithm.model.PropagationPath>of();
        ProductionDataProvider.Selection selected = new ProductionDataProvider.Selection(List.of(), List.of());
        EmbeddingOutcome embedded = null; var pairs = new ArrayList<AnalysisOutcome.PairResult>();
        int planned = 0, executed = 0, rejected = 0, failed = 0;
        String status;
        if (cancelled.getAsBoolean()) status = "CANCELLED";
        else if (frontier.frontier() == null) status = "EXECUTION_UNAVAILABLE";
        else {
            paths = new BomFrontierReconciler(frontier.frontier()).reconcile(actual.graph(), comparable.graph(), command.leafThreshold(), command.stopThreshold());
            reports.set(reports.size() - 1, executed(frontier.report()));
            emit.accept("branches", paths);
            if (paths.isEmpty()) status = "NO_ABNORMAL_BRANCHES";
            else if (command.comparison() == null || command.comparison().evidence() == null) status = "COMPARISON_INPUT_REQUIRED";
            else if (cancelled.getAsBoolean()) status = "CANCELLED";
            else {
                selected = productions.load(actual, comparable, paths, command.comparison().evidence());
                emit.accept("production-trees", selected);
                var plannedPairs = new ProductionPairSelector().select(selected.trees()); planned = plannedPairs.size();
                var fgw = backends.resolve("FGW", policy); reports.add(fgw.report());
                EmbeddingProvider provider = null;
                try {
                    if (!cancelled.getAsBoolean()) {
                        try { provider = providers.apply(settings); }
                        catch (RuntimeException missing) { diagnostics.add("EMBEDDING_INPUT_REQUIRED:" + missing.getMessage()); }
                    }
                    var contextInputs = command.comparison().contextEvidence() == null ? Map.<String, MaterialContext.Evidence>of() : command.comparison().contextEvidence();
                    embedded = preparation.prepare(selected.trees(), (tree, occurrence) -> contextInputs.containsKey(occurrence.occurrenceId())
                            ? contextInputs.get(occurrence.occurrenceId()) : contexts.evidence(tree, occurrence), provider, settings,
                            command.comparison().embeddings() == null ? Map.of() : command.comparison().embeddings(), cancelled);
                } finally { if (provider != null) provider.close(); }
                if (embedded.execution() != null) {
                    var inference = embedded.execution();
                    reports.add(new ExecutionReport("EMBEDDING", null, settings.backend(), inference.backend(), inference.device(),
                            inference.fallbackReason(), inference.precision(), embedded.submittedInputs() > 0 ? (embedded.inferredInputs() == 0 ? "FAILED" : embedded.unresolved() > 0 ? "PARTIAL" : "EXECUTED")
                            : settings.mode() == EmbeddingSettings.Mode.SUPPLIED_ONLY ? "SUPPLIED_ONLY" : "NO_INFERENCE"));
                }
                emit.accept("embeddings", embedded);
                var adapter = fgw.fgw() == null ? null : new ProductionTreeFgwAdapter(fgw.fgw());
                try {
                for (var pair : plannedPairs) {
                    String disposition; ProductionTreeFgwAdapter.Result result = null; String error = null;
                    if (cancelled.getAsBoolean()) { disposition = "REJECTED"; error = "CANCELLED"; rejected++; }
                    else if (adapter == null) { disposition = "REJECTED"; error = "FGW_EXECUTION_UNAVAILABLE"; rejected++; }
                    else {
                        try {
                            result = adapter.compare(List.of(pair.left(), pair.right()), new ProductionTreeFgwAdapter.Request(
                                    pair.left().productionId(), pair.right().productionId(), embedded.vectors(), command.comparison().config(), List.of()));
                            if (result.result() == null) { disposition = "REJECTED"; rejected++; }
                            else if (result.result().starts.get(result.result().bestStart).status == FgwEngine.Status.NUMERICAL_FAILURE) {
                                disposition = "NUMERICAL_FAILURE"; failed++;
                            } else { disposition = "EXECUTED"; executed++; }
                        } catch (RuntimeException failure) {
                            if (failure instanceof java.util.concurrent.CancellationException) { disposition = "REJECTED"; rejected++; }
                            else { disposition = "NUMERICAL_FAILURE"; failed++; }
                            error = failure.getMessage();
                        }
                    }
                    var output = new AnalysisOutcome.PairResult(pair.left().namespace(), pair.left().productId(), pair.left().productionId(),
                            pair.right().productionId(), disposition, result, error);
                    pairs.add(output); emit.accept("comparison", output);
                }
                } finally { if (fgw.fgw() != null) fgw.fgw().close(); }
                if (executed + failed > 0) {
                    for (int i = 0; i < reports.size(); i++) if (reports.get(i).stage().equals("FGW")) reports.set(i, executed(fgw.report()));
                }
                status = cancelled.getAsBoolean() ? "CANCELLED" : planned == 0 ? "INSUFFICIENT_PRODUCTION_INSTANCES"
                        : failed > 0 ? "NUMERICAL_FAILURE" : rejected > 0 ? "COMPARISON_INPUT_REQUIRED" : "COMPLETED";
            }
        }
        var outcome = new AnalysisOutcome(id, status, actual, comparable, paths, selected, embedded, List.copyOf(pairs),
                planned, executed, rejected, failed, List.copyOf(reports), List.copyOf(diagnostics));
        emit.accept("completed", outcome); return outcome;
    }
    private static ExecutionReport executed(ExecutionReport r) {
        return new ExecutionReport(r.stage(), r.supplied(), r.requested(), r.actual(), r.device(), r.fallbackReason(), r.numericalMode(), "EXECUTED");
    }
    private static void dates(java.time.LocalDate start, java.time.LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) throw new IllegalArgumentException("Ordered period dates are required");
    }
}
