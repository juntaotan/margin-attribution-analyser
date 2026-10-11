package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder.RecordPeriodGraphProvider;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.*;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.*;
import dev.margintrace.margin_attribution_backend.algorithm.execution.*;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import dev.margintrace.margin_attribution_backend.infrastructure.embedding.PythonEmbeddingProvider;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.margintrace.margin_attribution_backend.algorithm.workflow.AnalysisFixtures.*;

class AnalysisWorkflowTest {
    static AnalysisWorkflow workflow(EmbeddingProvider provider) {
        return new AnalysisWorkflow(new RecordPeriodGraphProvider(records()), new RecordProductionDataProvider(), AnalysisFixtures::context, settings -> provider);
    }
    @Test void unrelatedProductionRootsOutsidePeriodGraphDoNotInvalidateSelection() {
        var original = records();
        var issues = new ArrayList<>(original.issues());
        issues.add(new ProductionTree.Issue("unrelated-line", SEPTEMBER.minusYears(1), "unrelated-document",
                "unrelated-production", "unrelated-product", "unrelated-material", d("1"), d("1"), d("1"), d("1")));
        // An unrelated historical production must not become a root of the selected period CSR.
        var inputs = new PeriodRecords(original.sales(), issues, true);
        var graphs = new RecordPeriodGraphProvider(inputs);
        var cmd = command();
        var actual = graphs.load(cmd.actualStartDate(), cmd.actualEndDate());
        var comparable = graphs.load(cmd.comparableStartDate(), cmd.comparableEndDate());
        var paths = new BomFrontierReconciler().reconcile(actual.graph(), comparable.graph(), cmd.leafThreshold(), cmd.stopThreshold());
        var selection = new RecordProductionDataProvider().load(actual, comparable, paths, cmd.comparison().evidence());
        assertTrees(selection.trees());
        assertTrue(selection.identities().isEmpty());
        for (var tree : selection.trees())
            assertEquals(Map.of(tree.productionId(), "A"), tree.topDownScope().sourceGraph().roots());
    }
    @Test void computationalStepsStartFromOriginalRecordsAndConnectSourceIdentities() {
        var graphs = new RecordPeriodGraphProvider(records()); var command = command();
        var actual = graphs.load(command.actualStartDate(), command.actualEndDate());
        var comparable = graphs.load(command.comparableStartDate(), command.comparableEndDate());
        assertEquals(List.of("A", "R1", "R2", "R3", "R5", "S1", "S2", "__COGS__"), Arrays.stream(actual.graph().nodes()).map(n -> n.inventoryId()).toList());
        assertArrayEquals(new int[]{0,1,2,3,4,5,6,7,7}, actual.graph().offset());
        assertArrayEquals(new int[]{7,5,5,6,6,0,0}, actual.graph().successors());
        var paths = new BomFrontierReconciler().reconcile(actual.graph(), comparable.graph(), command.leafThreshold(), command.stopThreshold());
        assertEquals(1, paths.size());
        assertEquals(List.of("R1", "S1", "A", "__COGS__"), paths.getFirst().positions().stream().map(i -> actual.graph().nodes()[i].inventoryId()).toList());
        assertEquals(0, d("30").compareTo(paths.getFirst().endingCostDifference()));
        var selection = new RecordProductionDataProvider().load(actual, comparable, paths, command.comparison().evidence()); assertTrees(selection.trees());
        var provider = new RecordingProvider();
        var prepared = new EmbeddingPreparation().prepare(selection.trees(), AnalysisFixtures::context, provider, EmbeddingSettings.cpu(), Map.of(), () -> false);
        assertEquals(14, prepared.manifests().size()); assertEquals(0, prepared.unresolved());
        for (var tree : selection.trees()) {
            var root = prepared.manifests().stream().filter(m -> m.occurrenceId().equals(tree.nodes().get(tree.rootIndex()).occurrenceId())).findFirst().orElseThrow();
            assertEquals(List.of("S1", "S2"), root.context().upstream().stream().map(MaterialContext.Neighbour::code).toList());
            assertTrue(root.context().downstream().isEmpty()); assertFalse(root.sourceHash().isBlank());
        }
        var pair = new ProductionPairSelector().select(selection.trees()).getFirst();
        var compared = new ProductionTreeFgwAdapter().compare(selection.trees(), new ProductionTreeFgwAdapter.Request(pair.left().productionId(), pair.right().productionId(), prepared.vectors(), command.comparison().config(), List.of()));
        assertNotNull(compared.result()); assertEquals(7, compared.result().rows); assertEquals(7, compared.result().columns);
        var events = new ArrayList<AnalysisProgressListener.Event>(); var outcome = workflow(provider).run(command, events::add);
        assertEquals("COMPLETED", outcome.status()); assertEquals(1, outcome.planned()); assertEquals(1, outcome.executed());
        assertEquals(0, outcome.rejected()); assertEquals(0, outcome.failed()); assertTrue(provider.closed);
        assertEquals(List.of("graph", "branches", "production-trees", "embeddings", "comparison", "completed"), events.stream().map(AnalysisProgressListener.Event::type).toList());
        for (int i = 0; i < events.size(); i++) { assertEquals(i + 1, events.get(i).sequence()); assertEquals(outcome.analysisId(), events.get(i).analysisId()); }
    }
    static void assertTrees(List<ProductionTree> trees) {
        assertEquals(List.of("P0", "P1"), trees.stream().map(ProductionTree::productionId).toList());
        for (var tree : trees) {
            boolean actual = tree.productionId().equals("P1");
            assertEquals(7, tree.nodes().size()); assertEquals(6, tree.edges().size()); assertEquals(4, tree.topDownScope().terminalNodeIds().size());
            assertEquals(4, tree.topDownScope().paths().size()); assertTrue(tree.directComplete(), tree.statuses().toString()); assertTrue(tree.lineageComplete(), tree.statuses().toString());
            assertTrue(tree.costComplete(), tree.statuses().toString()); assertEquals(actual ? "250" : "220", tree.recordedMaterialAmount());
            assertEquals(actual ? "270" : "240", tree.calculatedTotalCost()); assertEquals("12", tree.laborCost()); assertEquals("8", tree.overheadCost());
            assertEquals("10", tree.outputQuantity()); assertEquals(actual ? d("27") : d("24"), d(tree.calculatedTotalCost()).divide(d(tree.outputQuantity())));
            assertEquals("20", tree.outputCostDifference()); assertEquals("0", tree.totalOutputCostDifference());
            assertTrue(tree.nodes().stream().noneMatch(n -> n.materialId().equals("__COGS__")));
            var steel = tree.nodes().stream().filter(n -> n.materialId().equals("R1")).findFirst().orElseThrow(); assertEquals("20", steel.quantity()); assertEquals(actual ? "120" : "100", steel.amount());
            var coat = tree.nodes().stream().filter(n -> n.materialId().equals(actual ? "R5" : "R4")).findFirst().orElseThrow(); assertEquals("5", coat.quantity()); assertEquals(actual ? "40" : "30", coat.amount());
            assertEquals(6, tree.issueDecisions().stream().filter(ProductionTree.IssueDecision::accepted).count());
        }
    }
    @Test void primaryTreeExclusionRetainsUnrelatedTerminalCandidate() {
        var original=records(); var rows=new ArrayList<>(original.issues());
        rows.add(new ProductionTree.Issue("unrelated-R1",SEPTEMBER,"unrelated-document","Q-S1","S1","R1",d("3"),d("18"),d("10"),d("18")));
        var inputs=new PeriodRecords(original.sales(),rows,true);var graphs=new RecordPeriodGraphProvider(inputs);var cmd=command();
        var a=graphs.load(cmd.actualStartDate(),cmd.actualEndDate());var c=graphs.load(cmd.comparableStartDate(),cmd.comparableEndDate());
        var selection=new RecordProductionDataProvider().load(a,c,new BomFrontierReconciler().reconcile(a.graph(),c.graph(),cmd.leafThreshold(),cmd.stopThreshold()),evidence(inputs));
        assertTrees(selection.trees());var p1=selection.trees().getLast();
        assertTrue(p1.sourceIssues().stream().anyMatch(i->i.lineId().equals("unrelated-R1")));
        assertTrue(p1.issueDecisions().stream().anyMatch(i->i.issueLineId().equals("unrelated-R1")&&!i.accepted()&&i.reason().equals("RECEIVING_OR_SOURCE_PRODUCTION_OUTSIDE_ROOT_PATH")));
        assertEquals(1,a.sources().get("P1-S1:R1").materialPosition());assertEquals(5,a.sources().get("P1-S1:R1").receivingPosition());
    }
    @Test void noPathsNeverLoadInstancesOrInferAndMissingEvidenceRetainsDiscovery() {
        var cmd = command();
        var noPaths = new AnalysisCommand(cmd.actualStartDate(), cmd.actualEndDate(), cmd.comparableStartDate(), cmd.comparableEndDate(), d("999"), d("999"), cmd.execution(), cmd.embedding(), cmd.comparison());
        var pipeline = new AnalysisWorkflow(new RecordPeriodGraphProvider(records()), (a,c,p,e) -> { fail("Instance loading after no paths"); return null; }, (t,n) -> { fail("Context loading after no paths"); return null; }, s -> { fail("Inference after no paths"); return null; });
        assertEquals("NO_ABNORMAL_BRANCHES", pipeline.run(noPaths).status());
        var missing = new AnalysisCommand(cmd.actualStartDate(), cmd.actualEndDate(), cmd.comparableStartDate(), cmd.comparableEndDate(), cmd.leafThreshold(), cmd.stopThreshold(), cmd.execution(), cmd.embedding(), null);
        var outcome = workflow(new RecordingProvider()).run(missing); assertEquals("COMPARISON_INPUT_REQUIRED", outcome.status()); assertEquals(1, outcome.branches().size());
        var unavailable = new AnalysisWorkflow(new RecordPeriodGraphProvider(records()), new RecordProductionDataProvider(), (t,n) -> null, s -> null).run(cmd);
        assertEquals(1, unavailable.planned()); assertEquals(1, unavailable.rejected()); assertEquals(14, unavailable.embeddings().unresolved());
    }
    @Test void executionPolicyCancellationAndAllPairsAreTruthful() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionPolicy(true, null, null, false));
        var registry = new BackendRegistry();
        for (boolean nvidia : List.of(true, false)) {
            var unavailable = registry.resolve("FGW", new ExecutionPolicy(true, nvidia, "2147483647", false)); assertNull(unavailable.fgw()); assertEquals(nvidia ? "CUDA" : "OPENCL", unavailable.report().requested());
            var fallback = registry.resolve("FGW", new ExecutionPolicy(true, nvidia, "2147483647", true)); assertNotNull(fallback.fgw()); assertEquals("JAVA_CPU", fallback.report().actual()); assertNotNull(fallback.report().fallbackReason());
        }
        assertEquals("CANCELLED", workflow(new RecordingProvider()).run(command(), e -> {}, () -> true).status());
        var base = workflow(new RecordingProvider()).run(command()).selection().trees().getFirst(); var pairs = new ProductionPairSelector();
        assertEquals(0, pairs.select(List.of()).size()); assertEquals(0, pairs.select(List.of(base)).size());
        assertEquals(3, pairs.select(java.util.stream.IntStream.range(0,3).mapToObj(i -> copyIdentity(base, "P" + i, base.namespace(), base.productId())).toList()).size());
        assertEquals(4950, pairs.select(java.util.stream.IntStream.range(0,100).mapToObj(i -> copyIdentity(base, "P" + i, base.namespace(), base.productId())).toList()).size());
        assertEquals(0, pairs.select(List.of(base, base, copyIdentity(base,"OTHER","other",base.productId()),copyIdentity(base,"OTHER",base.namespace(),"other"))).size());
        assertEquals(0,pairs.select(List.of(copyIdentity(base,"P1","a\u0000b","c"),copyIdentity(base,"P2","a","b\u0000c"))).size());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "MARGIN_TEST_GPU", matches = "true")
    void actualGpuWorkflowRetainsOriginalRecordEvidenceAndCpuParity() throws Exception {
        var expected = workflow(new RecordingProvider()).run(command());
        for (boolean cuda : List.of(true, false)) {
            var c = command();
            var gpuCommand = new AnalysisCommand(c.actualStartDate(), c.actualEndDate(), c.comparableStartDate(), c.comparableEndDate(),
                    c.leafThreshold(), c.stopThreshold(), new ExecutionPolicy(true, cuda, null, false), c.embedding(), c.comparison());
            var result = workflow(new RecordingProvider()).run(gpuCommand);
            assertEquals("COMPLETED", result.status(), result.diagnostics().toString());
            assertTrees(result.selection().trees()); assertEquals(expected.branches(), result.branches());
            assertEquals(expected.planned(), result.planned()); assertEquals(expected.executed(), result.executed());
            assertTrue(result.execution().stream().filter(e -> Set.of("FRONTIER", "FGW").contains(e.stage()))
                    .allMatch(e -> e.actual().equals(cuda ? "CUDA" : "OPENCL") && e.status().equals("EXECUTED") && e.device() != null));
            var actual = result.pairs().getFirst().comparison().result(); var cpu = expected.pairs().getFirst().comparison().result();
            assertEquals(cpu.starts.get(cpu.bestStart).costs.fgw(), actual.starts.get(actual.bestStart).costs.fgw(), 1e-8);
            assertEquals(cpu.starts.get(cpu.bestStart).status, actual.starts.get(actual.bestStart).status);
            var out = Path.of("target/native-validation"); Files.createDirectories(out);
            new ObjectMapper().writeValue(out.resolve((cuda ? "cuda" : "opencl") + "-original-record-workflow.json").toFile(), result);
        }
    }
    static ProductionTree copyIdentity(ProductionTree t, String id, String namespace, String product) {
        return new ProductionTree(namespace, id, namespace + ":" + id, product, t.scope(), t.rootIndex(), t.nodes(), t.edges(), t.sourceIssues(), t.rowOffsets(), t.colIndices(), t.outputQuantity(), t.recordedOutputCost(), t.recordedMaterialAmount(), t.outputCostDifference(), t.statuses(), t.directComplete(), t.lineageComplete(), t.selectionReferences(), t.topDownScope(), t.issueDecisions(), t.processingDecisions(), t.calculatedTotalCost(), t.knownTotalCost(), t.laborCost(), t.overheadCost(), t.costComplete(), t.totalOutputCostDifference());
    }
    @Test void allPairsReusePreparedVectorsAndCrossPeriodFullInstancesStayVisible() {
        var original=records();var issues=new ArrayList<>(original.issues());issues.addAll(rows("P2",SEPTEMBER));
        var sales=new ArrayList<>(original.sales());sales.add(new PeriodRecords.Sale("sale-P2",SEPTEMBER,"A",d("10"),d("240")));
        var inputs=new PeriodRecords(sales,issues,true);var cmd=command();var provider=new RecordingProvider();
        var three=new AnalysisCommand(cmd.actualStartDate(),cmd.actualEndDate(),cmd.comparableStartDate(),cmd.comparableEndDate(),cmd.leafThreshold(),cmd.stopThreshold(),cmd.execution(),cmd.embedding(),new AnalysisCommand.Comparison(evidence(inputs),true,Map.of(),Map.of(),cmd.comparison().config()));
        var outcome=new AnalysisWorkflow(new RecordPeriodGraphProvider(inputs),new RecordProductionDataProvider(),AnalysisFixtures::context,s->provider).run(three);
        assertEquals(3,outcome.planned());assertEquals(3,outcome.executed());assertEquals(21,outcome.embeddings().generated());assertEquals(outcome.embeddings().uniqueInputs(),provider.seen.size());
        assertEquals(List.of("P0/P1","P0/P2","P1/P2"),outcome.pairs().stream().map(p->p.leftProductionId()+"/"+p.rightProductionId()).toList());
        assertTrue(outcome.execution().stream().filter(r->Set.of("FRONTIER","FGW","EMBEDDING").contains(r.stage())).allMatch(r->r.status().equals("EXECUTED")));
        var row=issues.getFirst();issues.add(new ProductionTree.Issue("outside-report",SEPTEMBER.plusMonths(1),"outside-document",row.productionId(),row.productId(),row.materialId(),row.quantity(),row.amount(),row.outputQuantity(),row.outputCost()));
        var outside=new PeriodRecords(sales,issues,true);var graphs=new RecordPeriodGraphProvider(outside);var a=graphs.load(cmd.actualStartDate(),cmd.actualEndDate());var c=graphs.load(cmd.comparableStartDate(),cmd.comparableEndDate());
        var extra=evidence(outside);var processing=evidence(inputs);
        var outsideEvidence=new ProductionTree.Evidence(extra.namespace(),extra.scope(),extra.completeProductionIds(),extra.issues(),extra.outputs(),extra.currency(),extra.materialCostScope(),extra.totalCostScope(),processing.processingSources(),processing.processingAllocations());
        var selected=new RecordProductionDataProvider().load(a,c,new BomFrontierReconciler().reconcile(a.graph(),c.graph(),cmd.leafThreshold(),cmd.stopThreshold()),outsideEvidence);
        var p0=selected.trees().getFirst();assertTrue(p0.sourceIssues().stream().anyMatch(i->i.lineId().equals("outside-report")));assertFalse(p0.directComplete());assertTrue(p0.statuses().contains("INCOMPLETE_GRAPH_PERIOD_COVERAGE"));
    }
    @Test void numericalFailureIsDistinctFromRejectedEligibility() {
        var provider=new RecordingProvider(){@Override public Map<String,double[]> encode(List<Input> inputs){
            var vectors=new HashMap<String,double[]>();for(var input:inputs){var v=new double[384];v[0]=input.text().contains("unit cost=27 NZD")?-1e154:1e154;vectors.put(input.hash(),v);}return vectors;
        }};
        var outcome=workflow(provider).run(command());assertEquals("NUMERICAL_FAILURE",outcome.status());assertEquals(1,outcome.planned());assertEquals(0,outcome.executed());assertEquals(0,outcome.rejected());assertEquals(1,outcome.failed());
        assertEquals(dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.Status.NUMERICAL_FAILURE,outcome.pairs().getFirst().comparison().result().starts.getFirst().status);
    }
    @Test void interruptedExactOtAndSolverConfigurationFailExplicitly() {
        var config=new dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.Config();
        Thread.currentThread().interrupt();
        try {assertThrows(java.util.concurrent.CancellationException.class,()->dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.exactLinearOt(new double[]{0,1,1,0},2,2,config,new dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine.OtWorkspace()));}
        finally {Thread.interrupted();}
        config.alpha=2;var cmd=command();
        var invalid=new AnalysisCommand(cmd.actualStartDate(),cmd.actualEndDate(),cmd.comparableStartDate(),cmd.comparableEndDate(),cmd.leafThreshold(),cmd.stopThreshold(),cmd.execution(),cmd.embedding(),new AnalysisCommand.Comparison(cmd.comparison().evidence(),true,Map.of(),Map.of(),config));
        assertThrows(IllegalArgumentException.class,()->workflow(new RecordingProvider()).run(invalid));
    }
    @Test void realModelSensitivityAndWarmCacheEvidence() throws Exception {
        String python=System.getenv("MARGIN_EMBEDDING_PYTHON"),model=System.getenv("MARGIN_EMBEDDING_MODEL"),revision=System.getenv("MARGIN_EMBEDDING_REVISION");
        org.junit.jupiter.api.Assumptions.assumeTrue(python!=null&&model!=null&&revision!=null,"Real-model prerequisites not configured; UNVERIFIED");
        long started=System.nanoTime();
        try(var provider=new PythonEmbeddingProvider(python,"src/main/resources/embedding/sentence_encoder.py",model,revision,EmbeddingSettings.cpu(),60)) {
            double cold=(System.nanoTime()-started)/1e6;
            var trees=EmbeddingPreparationTest.trees();var prep=new EmbeddingPreparation();
            var baseline=prep.prepare(trees,AnalysisFixtures::context,provider,EmbeddingSettings.cpu(),Map.of(),()->false);
            var cached=prep.prepare(trees,AnalysisFixtures::context,provider,EmbeddingSettings.cpu(),Map.of(),()->false);
            assertEquals(14,cached.cached());assertEquals(0,cached.inferredInputs());
            var manifest=baseline.manifests().stream().filter(m->m.productionId().equals("P1")&&m.context().code().equals("R1")).findFirst().orElseThrow();
            var original=manifest.context();var e=original.evidence();
            var specs=new MaterialContext.Description(e.description().name(),"SUS304; 4 mm thickness",e.description().category(),e.description().references());
            var specification=new MaterialContext.Evidence(specs,e.sourceKind(),e.suppliers(),e.unitCost(),e.currency(),e.unit(),e.costScope(),e.valuationBasis(),e.references(),false);
            var supplier=new MaterialContext.Evidence(e.description(),e.sourceKind(),List.of("Steel Supplier Beta"),e.unitCost(),e.currency(),e.unit(),e.costScope(),e.valuationBasis(),e.references(),false);
            var price=new MaterialContext.Evidence(e.description(),e.sourceKind(),e.suppliers(),e.unitCost().add(d("1")),e.currency(),e.unit(),e.costScope(),e.valuationBasis(),e.references(),false);
            var variants=new LinkedHashMap<String,MaterialContext>();
            variants.put("code-renaming",new MaterialContext("MAT-100",e,original.upstream(),original.downstream()));
            variants.put("grade-specification",new MaterialContext(original.code(),specification,original.upstream(),original.downstream()));
            variants.put("supplier-change",new MaterialContext(original.code(),supplier,original.upstream(),original.downstream()));
            variants.put("unit-cost-change",new MaterialContext(original.code(),price,original.upstream(),original.downstream()));
            variants.put("neighbour-direction",new MaterialContext(original.code(),e,original.downstream(),original.upstream()));
            var neighbour=original.downstream().getFirst();
            variants.put("neighbour-identity",new MaterialContext(original.code(),e,original.upstream(),List.of(new MaterialContext.Neighbour(neighbour.occurrenceId(),"S9","Stainless enclosure assembly","Stainless; 4 mm wall"))));
            var batch=variants.entrySet().stream().map(v->new EmbeddingProvider.Input(v.getKey(),v.getValue().canonical())).toList();
            for(var input:batch)assertTrue(provider.tokenCount(input.text())<=provider.maximumTokens());
            started=System.nanoTime();var generated=provider.encode(batch);double warm=(System.nanoTime()-started)/1e6;
            var rows=new ArrayList<Map<String,Object>>();
            for(var variant:variants.entrySet()) {
                var value=generated.get(variant.getKey());assertTrue(EmbeddingPreparation.validateVector(value).isEmpty());
                var vectors=new HashMap<>(baseline.vectors());vectors.put(manifest.occurrenceId(),new ProductionTreeFgwAdapter.Embedding(provider.metadata().signature(),value));
                var compared=new ProductionTreeFgwAdapter().compare(trees,new ProductionTreeFgwAdapter.Request("P0","P1",vectors,command().comparison().config(),List.of()));assertNotNull(compared.result());
                var best=compared.result().starts.getFirst();double distance=0;var old=baseline.vectors().get(manifest.occurrenceId()).values();
                for(int i=0;i<384;i++)distance+=(old[i]-value[i])*(old[i]-value[i]);
                rows.add(Map.of("variant",variant.getKey(),"context",variant.getValue(),"input",variant.getValue().canonical(),"squaredEmbeddingChange",distance,"costs",best.costs,"correspondence",best.correspondence,"status",best.status));
            }
            var report=Path.of("target/analysis-validation");Files.createDirectories(report);
            new ObjectMapper().writeValue(report.resolve("model-quality.json").toFile(),Map.of("execution",provider.metadata(),"coldModelLoadMilliseconds",cold,"warmSixInputMilliseconds",warm,"initialPreparationMilliseconds",baseline.milliseconds(),"cacheHitMilliseconds",cached.milliseconds(),"variants",rows,"semanticAcceptance","UNVERIFIED: quality thresholds are not calibrated; no proportional monetary sensitivity is asserted"));
        }
    }
    @Test void realModelFullFlowFromOriginalRecords() throws Exception {
        String python = System.getenv("MARGIN_EMBEDDING_PYTHON"), model = System.getenv("MARGIN_EMBEDDING_MODEL"), revision = System.getenv("MARGIN_EMBEDDING_REVISION");
        org.junit.jupiter.api.Assumptions.assumeTrue(python != null && model != null && revision != null, "Real-model prerequisites not configured; UNVERIFIED");
        var provider = new PythonEmbeddingProvider(python, "src/main/resources/embedding/sentence_encoder.py", model, revision, EmbeddingSettings.cpu(), 60);
        var cmd=command();var graphs=new RecordPeriodGraphProvider(records());
        var actual=graphs.load(cmd.actualStartDate(),cmd.actualEndDate());var comparable=graphs.load(cmd.comparableStartDate(),cmd.comparableEndDate());
        assertArrayEquals(new int[]{7,5,5,6,6,0,0},actual.graph().successors());assertEquals(8,comparable.graph().nodes().length);
        var paths=new BomFrontierReconciler().reconcile(actual.graph(),comparable.graph(),cmd.leafThreshold(),cmd.stopThreshold());
        assertEquals(List.of("R1","S1","A","__COGS__"),paths.getFirst().positions().stream().map(i->actual.graph().nodes()[i].inventoryId()).toList());
        var restored=new RecordProductionDataProvider().load(actual,comparable,paths,cmd.comparison().evidence());assertTrees(restored.trees());
        var prepared=new EmbeddingPreparation().prepare(restored.trees(),AnalysisFixtures::context,provider,EmbeddingSettings.cpu(),Map.of(),()->false);
        assertEquals(14,prepared.generated());assertEquals(0,prepared.unresolved());assertEquals(12,prepared.inferredInputs());
        for(var vector:prepared.vectors().values())assertTrue(EmbeddingPreparation.validateVector(vector.values()).isEmpty());
        var stepwise=new ProductionTreeFgwAdapter().compare(restored.trees(),new ProductionTreeFgwAdapter.Request("P0","P1",prepared.vectors(),cmd.comparison().config(),List.of()));
        assertNotNull(stepwise.result());assertEquals(7,stepwise.result().rows);assertEquals(7,stepwise.result().columns);assertTrue(Double.isFinite(stepwise.result().starts.getFirst().costs.fgw()));
        long fullStarted=System.nanoTime();
        // This invocation reconstructs every stage again from original records; no stepwise arrays are injected.
        var full = workflow(provider).run(command()); double fullMilliseconds=(System.nanoTime()-fullStarted)/1e6;
        assertEquals("COMPLETED", full.status(), full.diagnostics().toString()); assertTrees(full.selection().trees());
        double maximumRepeatError=0;
        for(var entry:prepared.vectors().entrySet())for(int i=0;i<384;i++)maximumRepeatError=Math.max(maximumRepeatError,Math.abs(entry.getValue().values()[i]-full.embeddings().vectors().get(entry.getKey()).values()[i]));
        assertTrue(maximumRepeatError<=1e-6,"Pinned model CPU-repeat component tolerance is 1e-6; it does not relax FGW tolerance");
        assertEquals(14, full.embeddings().manifests().size()); assertEquals(14, full.embeddings().generated()); assertEquals(0, full.embeddings().unresolved()); assertEquals("CPU", full.embeddings().execution().backend());
        for (var vector : full.embeddings().vectors().values()) assertTrue(EmbeddingPreparation.validateVector(vector.values()).isEmpty());
        var report = Path.of("target/analysis-validation"); Files.createDirectories(report);
        new ObjectMapper().writeValue(report.resolve("full-flow.json").toFile(), full);
        var trees = full.selection().trees();
        new ObjectMapper().writeValue(report.resolve("numerical-inputs.json").toFile(), Map.of("left", ProductionTreeFgwAdapter.graph(trees.get(0), full.embeddings().vectors()), "right", ProductionTreeFgwAdapter.graph(trees.get(1), full.embeddings().vectors()), "result", full.pairs().getFirst().comparison().result(), "config", cmd.comparison().config(), "leafThreshold", cmd.leafThreshold().toPlainString(), "stopThreshold", cmd.stopThreshold().toPlainString(), "starts", List.of("UNIFORM_OUTER_PRODUCT")));
        new ObjectMapper().writeValue(report.resolve("stepwise-smoke.json").toFile(),Map.of("stepwiseInferredInputs",prepared.inferredInputs(),"completeInvocationInferredInputs",full.embeddings().inferredInputs(),"cpuRepeatTolerance",1e-6,"maximumRepeatComponentError",maximumRepeatError,"warmOriginalRecordsThroughFgwMilliseconds",fullMilliseconds,"stepwiseFgw",stepwise.result()));
    }
}
