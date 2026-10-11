package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFixtures.graph;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class ProductionTreeTwoPassTest {
    static final LocalDate DATE = LocalDate.of(2026,1,1);
    static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
    static Issue issue(String id, String production, String product, String material, String quantity, String amount, String output, String cost) {
        return new Issue(id,DATE,"shared-document",production,product,material,d(quantity),d(amount),d(output),d(cost));
    }
    static List<Issue> fixed() { return List.of(
            issue("L_RS","P_S","S","R","10","100","10","120"),
            issue("L_RB","P_B","B","R","99","990","1","990"),
            issue("L_SA","P_A","A","S","2","30","2","40"),
            issue("L_SC","P_C","C","S","8","96","8","96")); }
    static OutputEvidence totals() { return new OutputEvidence(OutputInterpretation.REPEATED_INSTANCE_TOTAL,
            OutputInterpretation.REPEATED_INSTANCE_TOTAL,List.of(),"each",true,"NZD","manufacturing"); }
    static ProcessingSource pool(String id, String production, String labor, String overhead) {
        return new ProcessingSource(id,production,"NZD","manufacturing",d(labor),d(overhead));
    }
    static ProcessingAllocation allocation(String id, String pool, String production, List<String> lines, String labor, String overhead) {
        return new ProcessingAllocation(id,pool,production,null,lines,"NZD","manufacturing","explicit relationship allocation",d(labor),d(overhead));
    }
    static Evidence evidence(List<Issue> rows) {
        Map<String,IssueEvidence> issues = new HashMap<>(); Map<String,OutputEvidence> outputs = new HashMap<>();
        Set<String> complete = new HashSet<>();
        for (Issue row : rows) {
            issues.put(row.lineId(),new IssueEvidence("kg",MaterialKind.RAW,null,null,null,null));
            outputs.put(row.productionId(),totals()); complete.add(row.productionId());
        }
        issues.put("L_SA",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"P_S",d("0.2"),null));
        return new Evidence("fixture",Scope.FULL_LINEAGE,complete,issues,outputs,"NZD","manufacturing","manufacturing",
                List.of(pool("source-S","P_S","12","8"),pool("source-A","P_A","4","6")),
                List.of(allocation("alloc-S","source-S","P_S",List.of("L_RS"),"12","8"),
                        allocation("alloc-A","source-A","P_A",List.of("L_SA"),"4","6")));
    }
    static Evidence costs(Evidence e, List<ProcessingSource> sources, List<ProcessingAllocation> allocations) {
        return new Evidence(e.namespace(),e.scope(),e.completeProductionIds(),e.issues(),e.outputs(),e.currency(),
                e.materialCostScope(),e.totalCostScope(),sources,allocations);
    }
    static ProductionTree build(String id, List<Issue> rows, Evidence e, ActualGraph g) {
        return new ProductionTreeBuilder().build(id,e,g,
                p -> rows.stream().filter(r -> r.productionId().equals(p)).toList(),
                m -> rows.stream().filter(r -> r.materialId().equals(m)).toList(),List.of("fixture-selection"));
    }
    static ActualGraph fixedGraph() { return graph("fixture",Map.of("P_A","A"),"A>S","S>R"); }

    @Test void fixedTwoPassCostsExclusionsAndOriginalValues() {
        ProductionTree tree = build("P_A",fixed(),evidence(fixed()),fixedGraph());
        assertTrue(tree.directComplete()); assertTrue(tree.lineageComplete()); assertTrue(tree.costComplete());
        assertEquals("30",tree.recordedMaterialAmount()); assertEquals("40",tree.calculatedTotalCost());
        assertEquals("4",tree.laborCost()); assertEquals("6",tree.overheadCost());
        assertEquals("0",tree.totalOutputCostDifference()); assertEquals("10",tree.outputCostDifference());
        Occurrence s = tree.nodes().get(1), r = tree.nodes().get(2);
        assertEquals("30",s.amount()); assertEquals("24",s.totalCost()); assertEquals("120",s.sourceTotalCost());
        assertEquals("24",s.projectedSourceTotalCost()); assertEquals("6",s.sourceValuationDifference());
        assertEquals("2",r.quantity()); assertEquals("20",r.amount()); assertEquals("20",r.totalCost());
        assertEquals("2.4",tree.edges().get(1).laborCost()); assertEquals("1.6",tree.edges().get(1).overheadCost());
        assertEquals("100",tree.sourceIssues().stream().filter(i -> i.lineId().equals("L_RS")).findFirst().map(i -> i.amount().toPlainString()).orElseThrow());
        assertEquals(4,tree.sourceIssues().size());
        assertTrue(tree.issueDecisions().stream().allMatch(d -> d.rootInstanceId().equals(tree.instanceId())));
        assertTrue(tree.processingDecisions().stream().allMatch(d -> d.rootInstanceId().equals(tree.instanceId())));
        assertEquals(Set.of("L_RS","L_SA"),new HashSet<>(tree.issueDecisions().stream().filter(IssueDecision::accepted).map(IssueDecision::issueLineId).toList()));
        assertTrue(tree.issueDecisions().stream().filter(d -> !d.accepted()).allMatch(d -> d.reason().equals("RECEIVER_OUTSIDE_SCOPE")));
        assertEquals(Set.of("L_RB","L_SC"),new HashSet<>(tree.issueDecisions().stream().filter(d -> !d.accepted()).map(IssueDecision::issueLineId).toList()));
        assertEquals(d("12"),tree.processingDecisions().get(1).allocation().labor());
        List<Issue> reordered = new ArrayList<>(fixed()); Collections.reverse(reordered); reordered.add(fixed().get(0));
        ProductionTree other = build("P_A",reordered,evidence(fixed()),ProductionScopeTraversalTest.permute(fixedGraph(),Direction.MATERIAL_TO_PRODUCT));
        assertEquals(tree.nodes(),other.nodes()); assertEquals(tree.edges(),other.edges()); assertEquals(tree.issueDecisions(),other.issueDecisions());
    }
    @Test void missingProcessingExplicitZerosPartialTotalsAndScopeMismatch() {
        Evidence e = evidence(fixed());
        Evidence missing = costs(e,e.processingSources(),List.of(e.processingAllocations().get(0)));
        ProductionTree incomplete = build("P_A",fixed(),missing,fixedGraph());
        assertEquals("30",incomplete.recordedMaterialAmount()); assertEquals("30",incomplete.knownTotalCost());
        assertNull(incomplete.calculatedTotalCost()); assertNull(incomplete.totalOutputCostDifference()); assertFalse(incomplete.costComplete());
        assertTrue(incomplete.statuses().containsAll(Set.of("MISSING_LABOR_ALLOCATION","MISSING_OVERHEAD_ALLOCATION","UNALLOCATED_PROCESSING_COST")));
        Evidence zero = costs(e,List.of(pool("source-S","P_S","12","8"),pool("source-A","P_A","0","0")),
                List.of(e.processingAllocations().get(0),allocation("alloc-A","source-A","P_A",List.of("L_SA"),"0","0")));
        ProductionTree explicit = build("P_A",fixed(),zero,fixedGraph());
        assertTrue(explicit.costComplete()); assertEquals("30",explicit.calculatedTotalCost()); assertEquals("0",explicit.laborCost());
        Evidence partial = costs(e,List.of(pool("source-S","P_S","12","8"),pool("source-A","P_A",null,"6")),
                List.of(e.processingAllocations().get(0),allocation("alloc-A","source-A","P_A",List.of("L_SA"),null,"6")));
        ProductionTree known = build("P_A",fixed(),partial,fixedGraph());
        assertEquals("36",known.knownTotalCost()); assertNull(known.calculatedTotalCost());
        assertEquals("24",known.nodes().get(1).totalCost());
        ProcessingAllocation wrong = new ProcessingAllocation("alloc-A","source-A","P_A",null,List.of("L_SA"),"USD","different","explicit",d("4"),d("6"));
        ProductionTree incompatible = build("P_A",fixed(),costs(e,e.processingSources(),List.of(e.processingAllocations().get(0),wrong)),fixedGraph());
        assertFalse(incompatible.costComplete()); assertEquals("30",incompatible.knownTotalCost());
        assertTrue(incompatible.statuses().contains("INCOMPATIBLE_PROCESSING_COST_SCOPE"));
    }
    @Test void bothEndpointMembershipDoesNotPermitCrossEdgeOrWrongBatch() {
        List<Issue> rows = new ArrayList<>(fixed());
        rows.add(issue("cross","P_A","A","R","50","500","2","40"));
        rows.add(issue("other-A","P_OTHER","A","S","7","70","7","70"));
        ProductionTree tree = build("P_A",rows,evidence(rows),fixedGraph());
        assertEquals("30",tree.recordedMaterialAmount()); assertFalse(tree.directComplete());
        assertTrue(tree.issueDecisions().stream().anyMatch(i -> i.issueLineId().equals("cross") && !i.accepted() && i.reason().equals("RELATIONSHIP_OUTSIDE_SCOPE")));
        assertTrue(tree.issueDecisions().stream().anyMatch(i -> i.issueLineId().equals("other-A") && !i.accepted() && i.reason().equals("RECEIVING_OR_SOURCE_PRODUCTION_OUTSIDE_ROOT_PATH")));
        rows.add(issue("wrong-source","P_WRONG","S","R","100","1000","10","1000"));
        tree = build("P_A",rows,evidence(rows),fixedGraph());
        assertEquals("20",tree.nodes().get(2).amount());
        assertTrue(tree.issueDecisions().stream().anyMatch(i -> i.issueLineId().equals("wrong-source") && !i.accepted()));
    }
    @Test void suppliedScopeCannotBeReplacedByOutputGraphAndPeriodCoverageIsSeparate() {
        Evidence e = evidence(fixed());
        assertEquals("MISSING_PRECEDING_ACTUAL_GRAPH",assertThrows(IllegalArgumentException.class,
                () -> build("P_A",fixed(),e,null)).getMessage());
        ActualGraph limited = new ActualGraph("limited","fixture",Direction.PRODUCT_TO_MATERIAL,true,
                fixedGraph().nodes(),fixedGraph().rowOffsets(),fixedGraph().colIndices(),fixedGraph().edgeIds(),fixedGraph().roots(),true,
                DATE.plusDays(1),DATE.plusDays(2));
        ProductionTree tree = build("P_A",fixed(),e,limited);
        assertFalse(tree.directComplete()); assertFalse(tree.costComplete());
        assertTrue(tree.statuses().contains("INCOMPLETE_GRAPH_PERIOD_COVERAGE")); assertEquals("30",tree.recordedMaterialAmount());
        ProductionTree missing = build("P_A",fixed(),e,graph("fixture",Map.of("P_A","A"),"A>R"));
        assertNull(missing.recordedMaterialAmount()); assertFalse(missing.directComplete());
        assertTrue(missing.issueDecisions().stream().anyMatch(d -> d.issueLineId().equals("L_SA") && !d.accepted()));
    }
    @Test void poolSplittingCountedOnceUnsupportedAllocationAndOverdrawRejected() {
        List<Issue> rows = ProductionTreeBuilderTest.fixed();
        Evidence base = ProductionTreeBuilderTest.evidence(rows,Scope.DIRECT_CONSUMPTION,Map.of());
        Evidence e = new Evidence(base.namespace(),base.scope(),base.completeProductionIds(),base.issues(),base.outputs(),
                "NZD","manufacturing","manufacturing",List.of(pool("pool-P1","P1","12","8")),List.of(
                allocation("B","pool-P1","P1",List.of("L1","L2"),"9","6"),
                allocation("C","pool-P1","P1",List.of("L3"),"3","2")));
        ActualGraph graph = graph("fixture",Map.of("P1","A","P2","A"),"A>B","A>C");
        ProductionTree tree = build("P1",rows,e,graph);
        assertEquals("320",tree.calculatedTotalCost()); assertEquals("300",tree.recordedMaterialAmount());
        assertEquals("12",tree.laborCost()); assertEquals("8",tree.overheadCost());
        List<ProcessingAllocation> duplicatedQuery = new ArrayList<>(e.processingAllocations()); duplicatedQuery.add(e.processingAllocations().get(0));
        assertEquals(tree.nodes(),build("P1",rows,costs(e,e.processingSources(),duplicatedQuery),graph).nodes());
        List<ProcessingAllocation> duplicateTotals = List.of(allocation("B","pool-P1","P1",List.of("L1","L2"),"12","8"),
                allocation("C","pool-P1","P1",List.of("L3"),"12","8"));
        assertEquals("PROCESSING_ALLOCATION_EXCEEDS_SOURCE_TOTAL",assertThrows(IllegalArgumentException.class,
                () -> build("P1",rows,costs(e,e.processingSources(),duplicateTotals),graph)).getMessage());
        ProcessingAllocation unsupported = new ProcessingAllocation("unsupported","pool-P1","P1",null,List.of(),"NZD","manufacturing",null,d("12"),d("8"));
        ProductionTree unallocated = build("P1",rows,costs(e,e.processingSources(),List.of(unsupported)),graph);
        assertEquals("300",unallocated.knownTotalCost()); assertFalse(unallocated.costComplete());
        assertTrue(unallocated.processingDecisions().stream().anyMatch(d -> d.reason().equals("UNSUPPORTED_PROCESSING_ALLOCATION")));
    }
    @Test void convergingPathsTerminalVisitsAndSharedSourceProjectionsDoNotDuplicate() {
        List<Issue> rows = List.of(issue("d1","P_A","A","S","2","30","2","60"),
                issue("d2","P_A","A","S","3","45","2","60"),
                issue("raw-direct","P_A","A","R","0.1","1","2","60"),
                issue("up","P_S","S","R","10","100","10","120"));
        Evidence base = ProductionTreeBuilderTest.evidence(rows,Scope.FULL_LINEAGE,Map.of(
                "d1",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"P_S",d("0.2"),null),
                "d2",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"P_S",d("0.3"),null)));
        ActualGraph graph = graph("fixture",Map.of("P_A","A"),"A>S","A>R","S>R");
        List<String> queries = new ArrayList<>();
        ProductionTree tree = new ProductionTreeBuilder().build("P_A",base,graph,
                p -> rows.stream().filter(r -> r.productionId().equals(p)).toList(),
                m -> { queries.add(m); List<Issue> result = new ArrayList<>(rows.stream().filter(r -> r.materialId().equals(m)).toList());
                    result.addAll(new ArrayList<>(result)); return result; },List.of());
        assertEquals(List.of("R","S","A"),queries); assertEquals("76",tree.recordedMaterialAmount());
        assertEquals(4,tree.sourceIssues().size()); assertEquals(2,tree.issueDecisions().stream().filter(d -> d.accepted() && d.issueLineId().equals("up")).count());
        assertEquals(d("5"),tree.nodes().stream().filter(n -> n.issueLineIds().contains("up")).map(n -> d(n.quantity())).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals(d("50"),tree.nodes().stream().filter(n -> n.issueLineIds().contains("up")).map(n -> d(n.amount())).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertTrue(tree.lineageComplete());
    }
    @Test void everyTerminalIsQueriedBeforeIntermediatesAndSourceShareConflictsAreExplicit() {
        List<Issue> rows = new ArrayList<>(fixed()); rows.add(issue("direct-Z","P_A","A","Z","1","1","2","40"));
        Evidence e = evidence(rows); List<String> queries = new ArrayList<>();
        ProductionTree tree = new ProductionTreeBuilder().build("P_A",e,graph("fixture",Map.of("P_A","A"),"A>S","S>R","A>Z"),
                p -> rows.stream().filter(i -> i.productionId().equals(p)).toList(),
                m -> { queries.add(m); return rows.stream().filter(i -> i.materialId().equals(m)).toList(); },List.of());
        assertEquals(List.of("R","Z","S","A"),queries); assertEquals("31",tree.recordedMaterialAmount());
        Map<String,IssueEvidence> issues = new HashMap<>(e.issues());
        issues.put("L_SA",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"P_S",d("0.3"),null));
        Evidence conflict = new Evidence(e.namespace(),e.scope(),e.completeProductionIds(),issues,e.outputs(),e.currency(),e.materialCostScope(),e.totalCostScope(),e.processingSources(),e.processingAllocations());
        ProductionTree invalid = build("P_A",fixed(),conflict,fixedGraph());
        assertTrue(invalid.statuses().contains("SOURCE_SHARE_QUANTITY_CONFLICT")); assertFalse(invalid.lineageComplete());
        assertNull(invalid.nodes().get(1).projectedSourceTotalCost());
    }
    @Test void matchingTopologyScopeIsRequiredDespiteSameProductAndVectors() {
        List<Issue> rows = ProductionTreeBuilderTest.fixed();
        ProductionTreeBuilderTest baseline = new ProductionTreeBuilderTest();
        ProductionTree left = baseline.build("P1",rows,ProductionTreeBuilderTest.evidence(rows,Scope.DIRECT_CONSUMPTION,Map.of()));
        ProductionTree right = baseline.build("P2",rows,ProductionTreeBuilderTest.evidence(rows,Scope.FULL_LINEAGE,Map.of()));
        var result = new ProductionTreeFgwAdapter().compare(new dev.margintrace.margin_attribution_backend.analysis.dto.ProductionTreeResponse(
                List.of(left,right),List.of(),"","",null),new dev.margintrace.margin_attribution_backend.analysis.dto.ProductionComparisonRequest(
                null,"P1","P2",Map.of(),null,null));
        assertTrue(result.statuses().contains("INCOMPATIBLE_TOPOLOGY_SCOPE")); assertNull(result.result());
    }

    @Test void distinctGraphPositionsWithEqualCodesRequireExplicitEdgeIdentity() {
        List<Issue> rows = List.of(issue("a","P","A","B","1","2","1","6"),issue("b","P","A","B","2","4","1","6"));
        ActualGraph graph = new ActualGraph("positions","fixture",Direction.PRODUCT_TO_MATERIAL,true,
                List.of(new GraphNode("root","A"),new GraphNode("B1","B"),new GraphNode("B2","B")),
                new int[]{0,2,2,2},new int[]{1,2},List.of("first","second"),Map.of("P","root"),true,null,null);
        Evidence base = ProductionTreeBuilderTest.evidence(rows,Scope.DIRECT_CONSUMPTION,Map.of());
        ProductionTree ambiguous = build("P",rows,base,graph);
        assertFalse(ambiguous.directComplete()); assertTrue(ambiguous.statuses().contains("AMBIGUOUS_GRAPH_RELATIONSHIP"));
        Map<String,IssueEvidence> issues = Map.of("a",new IssueEvidence("kg",MaterialKind.RAW,null,null,null,null,"first"),
                "b",new IssueEvidence("kg",MaterialKind.RAW,null,null,null,null,"second"));
        Evidence explicit = new Evidence(base.namespace(),base.scope(),base.completeProductionIds(),issues,base.outputs(),base.currency(),base.materialCostScope());
        ProductionTree tree = build("P",rows,explicit,graph);
        assertTrue(tree.directComplete()); assertEquals(3,tree.nodes().size()); assertEquals("6",tree.recordedMaterialAmount());
        assertEquals(Set.of("first","second"),new HashSet<>(tree.edges().stream().map(Edge::graphEdgeId).toList()));
    }

    @Test void upstreamProcessingIncompletenessDoesNotEraseKnownReceiverValuation() {
        Evidence e = evidence(fixed());
        ProductionTree tree = build("P_A",fixed(),costs(e,e.processingSources(),List.of(e.processingAllocations().get(1))),fixedGraph());
        assertEquals("30",tree.recordedMaterialAmount()); assertEquals("40",tree.calculatedTotalCost()); assertTrue(tree.costComplete());
        assertEquals("20",tree.nodes().get(1).knownTotalCost()); assertNull(tree.nodes().get(1).totalCost());
        assertNull(tree.nodes().get(1).sourceValuationDifference());
        assertTrue(tree.edges().get(0).processingComplete()); assertFalse(tree.edges().get(1).processingComplete());
    }

}
