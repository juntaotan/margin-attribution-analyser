package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.margintrace.margin_attribution_backend.analysis.dto.*;

class ProductionTreeBuilderTest {
    final ProductionTreeBuilder builder = new ProductionTreeBuilder();
    static BigDecimal d(String value) { return value == null ? null : new BigDecimal(value); }
    static Issue row(String id, String production, String product, String material, String quantity, String amount, String output) {
        return new Issue(id, LocalDate.of(2026, 1, 1), "document-" + id, production, product, material,
                d(quantity), d(amount), d(output), d("300"));
    }
    static IssueEvidence raw() { return new IssueEvidence("kg", MaterialKind.RAW, null, null, null, null); }
    static OutputEvidence totals() { return new OutputEvidence(OutputInterpretation.REPEATED_INSTANCE_TOTAL,
            OutputInterpretation.REPEATED_INSTANCE_TOTAL, List.of(), "each", true); }
    static Evidence evidence(List<Issue> rows, Scope scope, Map<String, IssueEvidence> overrides) {
        Map<String, IssueEvidence> issues = new HashMap<>();
        Map<String, OutputEvidence> outputs = new HashMap<>();
        Set<String> ids = new HashSet<>();
        rows.forEach(r -> { issues.put(r.lineId(), raw()); outputs.put(r.productionId(), totals()); ids.add(r.productionId()); });
        issues.putAll(overrides);
        return new Evidence("fixture", scope, ids, issues, outputs, "NZD", "material");
    }
    ProductionTree build(String id, List<Issue> rows, Evidence e) {
        return builder.build(id, e, p -> rows.stream().filter(r -> r.productionId().equals(p)).toList(), List.of());
    }
    static List<Issue> fixed() { return List.of(
            row("L1", "P1", "A", "B", "100", "200", "20"),
            row("L2", "P1", "A", "B", "20", "60", "20"),
            row("L3", "P1", "A", "C", "5", "40", "20"),
            row("L4", "P2", "A", "B", "30", "90", "10"),
            row("L5", "P2", "A", "C", "2", "20", "10")); }

    @Test void fixedGroupingConservationReorderingAndSplitting() {
        List<Issue> rows = fixed();
        Evidence e = evidence(rows, Scope.DIRECT_CONSUMPTION, Map.of());
        ProductionTree p1 = build("P1", rows, e), p2 = build("P2", rows, e);
        assertEquals("20", p1.outputQuantity());
        assertEquals("10", p2.outputQuantity());
        assertEquals("300", p1.recordedMaterialAmount());
        assertEquals("110", p2.recordedMaterialAmount());
        assertEquals(3, p1.nodes().size());
        assertEquals("120", p1.nodes().get(1).quantity());
        assertEquals("260", p1.nodes().get(1).amount());
        assertEquals(List.of("L1", "L2"), p1.nodes().get(1).issueLineIds());
        assertEquals(d("150"), d(p1.nodes().get(1).quantity()).add(d(p2.nodes().get(1).quantity())));
        assertEquals(d("7"), d(p1.nodes().get(2).quantity()).add(d(p2.nodes().get(2).quantity())));
        assertEquals(d("410"), d(p1.recordedMaterialAmount()).add(d(p2.recordedMaterialAmount())));
        assertTrue(p1.directComplete());
        List<Issue> reversed = new ArrayList<>(rows); Collections.reverse(reversed); reversed.add(rows.get(0));
        assertEquals(p1.nodes(), build("P1", reversed, e).nodes());
        List<Issue> split = new ArrayList<>(rows.subList(1, rows.size()));
        split.add(row("L1a", "P1", "A", "B", "40", "80", "20"));
        split.add(row("L1b", "P1", "A", "B", "60", "120", "20"));
        ProductionTree s = build("P1", split, evidence(split, Scope.DIRECT_CONSUMPTION, Map.of()));
        assertEquals(p1.nodes().get(1).occurrenceId(), s.nodes().get(1).occurrenceId());
        assertEquals("120", s.nodes().get(1).quantity());
        assertEquals("300", s.recordedMaterialAmount());
        assertEquals(List.of("L1a", "L1b", "L2"), s.nodes().get(1).issueLineIds());
    }
    @Test void decimalPrecisionAndMissingSubtotals() {
        List<Issue> rows = List.of(row("a", "P", "A", "B", "0.1", "9007199254740993.01", "20"),
                row("b", "P", "A", "B", "0.2", "0.02", "20"), row("c", "P", "A", "B", null, null, "20"));
        ProductionTree tree = build("P", rows, evidence(rows, Scope.DIRECT_CONSUMPTION, Map.of()));
        assertEquals("0.3", tree.nodes().get(1).quantity());
        assertEquals("9007199254740993.03", tree.recordedMaterialAmount());
        assertTrue(tree.statuses().containsAll(Set.of("MISSING_MATERIAL_QUANTITY", "MISSING_MATERIAL_AMOUNT")));
        assertNull(tree.outputCostDifference());
        assertFalse(tree.directComplete());
    }
    @Test void missingAmbiguousAndScopedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> build("", fixed(), evidence(fixed(), Scope.DIRECT_CONSUMPTION, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> build("absent", fixed(), evidence(fixed(), Scope.DIRECT_CONSUMPTION, Map.of())));
        List<Issue> rows = List.of(row("a", "P", "A", "B", "1", "2", "1"), row("b", "P", "Z", "B", "1", "2", "1"));
        assertEquals("AMBIGUOUS_PRODUCTION_PRODUCT", assertThrows(IllegalArgumentException.class,
                () -> build("P", rows, evidence(rows, Scope.DIRECT_CONSUMPTION, Map.of()))).getMessage());
        assertNotEquals(ProductionTreeBuilder.scoped("a:b", "c"), ProductionTreeBuilder.scoped("a", "b:c"));
    }
    @Test void outputConflictsSemanticsReceiptsAndCostScopes() {
        List<Issue> rows = List.of(row("a", "P", "A", "B", "1", "2", "20"), row("b", "P", "A", "B", "1", "2", "21"));
        Evidence e = evidence(rows, Scope.DIRECT_CONSUMPTION, Map.of());
        ProductionTree conflict = build("P", rows, e);
        assertNull(conflict.outputQuantity()); assertTrue(conflict.statuses().contains("OUTPUT_QUANTITY_CONFLICT"));
        Evidence unverified = new Evidence(e.namespace(), e.scope(), e.completeProductionIds(), e.issues(), Map.of(), "NZD", "material");
        assertTrue(build("P", rows, unverified).statuses().contains("UNVERIFIED_OUTPUT_QUANTITY"));
        var receipts = new OutputEvidence(OutputInterpretation.RECEIPTS, OutputInterpretation.RECEIPTS,
                List.of(new Receipt("r1", d("10"), d("2")), new Receipt("r2", d("10"), d("2")),
                        new Receipt("r1", d("10"), d("2"))), "each", true);
        Evidence receiptEvidence = new Evidence(e.namespace(), e.scope(), e.completeProductionIds(), e.issues(), Map.of("P", receipts), "NZD", "material");
        ProductionTree tree = build("P", rows, receiptEvidence);
        assertEquals("20", tree.outputQuantity()); assertEquals("4", tree.recordedOutputCost());
        assertEquals("0", tree.outputCostDifference());
        assertTrue(build("P1", fixed(), evidence(fixed(), Scope.DIRECT_CONSUMPTION, Map.of())).statuses().isEmpty());
        assertTrue(build("P2", fixed(), evidence(fixed(), Scope.DIRECT_CONSUMPTION, Map.of())).statuses().contains("OUTPUT_COST_DISCREPANCY"));
        Issue conflictingCost = new Issue("c", rows.get(0).date(), "doc-c", "P", "A", "C", d("1"), d("2"), d("20"), d("301"));
        List<Issue> costs = List.of(rows.get(0), conflictingCost);
        ProductionTree costConflict = build("P", costs, evidence(costs, Scope.DIRECT_CONSUMPTION, Map.of()));
        assertTrue(costConflict.statuses().contains("OUTPUT_COST_CONFLICT"));
        assertNull(costConflict.recordedOutputCost()); assertFalse(costConflict.directComplete());
    }
    @Test void explicitPositionsAndDifferentSourcesRemainSeparate() {
        List<Issue> rows = List.of(row("step", "P", "A", "S", "1", "10", "1"),
                row("a", "P", "A", "B", "2", "4", "1"), row("b", "P", "A", "B", "3", "6", "1"),
                row("c", "P", "A", "B", "1", "2", "1"));
        Evidence e = evidence(rows, Scope.FULL_LINEAGE, Map.of(
                "step", new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, null, null, null),
                "a", new IssueEvidence("kg", MaterialKind.RAW, "step", null, null, null),
                "b", new IssueEvidence("kg", MaterialKind.RAW, null, "U1", d("0.1"), null),
                "c", new IssueEvidence("kg", MaterialKind.RAW, null, "U2", d("0.1"), null)));
        ProductionTree tree = build("P", rows, e);
        assertEquals(5, tree.nodes().size());
        assertEquals("18", tree.recordedMaterialAmount());
        var step = tree.nodes().stream().filter(n -> n.issueLineIds().contains("step")).findFirst().orElseThrow();
        var a = tree.nodes().stream().filter(n -> n.issueLineIds().contains("a")).findFirst().orElseThrow();
        assertEquals(step.index(), tree.edges().stream().filter(edge -> edge.materialIndex() == a.index()).findFirst().orElseThrow().receivingIndex());
        assertFalse(step.statuses().contains("UNRESOLVED_SOURCE_PRODUCTION"));
        assertTrue(tree.statuses().contains("MISSING_SOURCE_PRODUCTION"));
    }
    @Test void explicitUpstreamSharesAndFinancialNonDuplication() {
        List<Issue> rows = List.of(row("down", "P", "A", "S", "2", "50", "1"),
                row("up", "U", "S", "B", "100", "200", "10"));
        Evidence e = evidence(rows, Scope.FULL_LINEAGE, Map.of("down",
                new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, "U", d("0.2"), null)));
        ProductionTree tree = build("P", rows, e);
        assertEquals(3, tree.nodes().size());
        assertEquals("50", tree.recordedMaterialAmount());
        assertEquals("50", tree.nodes().get(1).amount());
        assertEquals("40", tree.nodes().get(2).amount());
        assertEquals("20", tree.nodes().get(2).quantity());
        assertEquals("U", tree.nodes().get(2).productionId());
        assertEquals(List.of("up"), tree.nodes().get(2).issueLineIds());
        assertEquals("10", tree.nodes().get(1).sourceOutputQuantity());
        assertTrue(tree.lineageComplete());
        assertArrayEquals(new int[]{0,0,1,2}, tree.rowOffsets());
        assertArrayEquals(new int[]{0,1}, tree.colIndices());
    }
    @Test void unresolvedBoundariesMissingCoverageClassificationAndUnits() {
        List<Issue> rows = List.of(row("a", "P", "A", "S", "1", "2", "1"));
        Evidence e = evidence(rows, Scope.FULL_LINEAGE, Map.of("a", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, null, null, null, null)));
        ProductionTree tree = build("P", rows, e);
        assertEquals(2, tree.nodes().size()); assertTrue(tree.statuses().contains("UNRESOLVED_SOURCE_PRODUCTION"));
        assertTrue(tree.directComplete()); assertFalse(tree.lineageComplete());
        Evidence missing = new Evidence("fixture", Scope.DIRECT_CONSUMPTION, Set.of(), Map.of(), e.outputs(), null, null);
        assertTrue(build("P", rows, missing).statuses().containsAll(Set.of("INCOMPLETE_IMPORTED_COVERAGE", "MISSING_UNIT", "MISSING_MATERIAL_CLASSIFICATION", "MISSING_COST_SCOPE")));
    }
    @Test void sharedSourcesRemainOccurrenceSpecificAndKeepOriginalEvidence() {
        List<Issue> rows = List.of(row("d1", "P", "A", "S", "2", "50", "1"),
                row("d2", "P", "A", "S", "3", "75", "1"), row("up", "U", "S", "B", "100", "200", "10"));
        Evidence e = evidence(rows, Scope.FULL_LINEAGE, Map.of(
                "d1", new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, "U", d("0.2"), null),
                "d2", new IssueEvidence("each", MaterialKind.INTERMEDIATE, null, "U", d("0.3"), null)));
        ProductionTree tree = build("P", rows, e);
        assertTrue(tree.lineageComplete());
        assertEquals(5, tree.nodes().size());
        assertEquals("125", tree.recordedMaterialAmount());
        assertEquals(d("50"), tree.nodes().stream().filter(n -> n.materialId().equals("B"))
                .map(n -> d(n.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(3, tree.sourceIssues().size());
        assertEquals(d("100"), tree.sourceIssues().stream().filter(i -> i.lineId().equals("up")).findFirst().orElseThrow().quantity());
        assertNotEquals(tree.nodes().get(3).occurrenceId(), tree.nodes().get(4).occurrenceId());
    }
    @Test void missingMaterialOutputsAndInvalidUpstreamEvidenceAreExplicit() {
        List<Issue> missing = List.of(row("a", "P", "A", null, null, null, null));
        ProductionTree tree = build("P", missing, evidence(missing, Scope.DIRECT_CONSUMPTION, Map.of()));
        assertTrue(tree.statuses().containsAll(Set.of("MISSING_MATERIAL", "MISSING_OUTPUT_QUANTITY", "MISSING_MATERIAL_AMOUNT", "MISSING_MATERIAL_QUANTITY")));
        assertNull(tree.recordedMaterialAmount());
        List<Issue> rows = List.of(row("d", "P", "A", "S", "2", "50", "1"), row("up", "U", "WRONG", "B", "100", "200", "10"));
        Evidence noShare = evidence(rows, Scope.FULL_LINEAGE, Map.of("d",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"U",null,null)));
        assertTrue(build("P",rows,noShare).statuses().contains("MISSING_SOURCE_SHARE"));
        Evidence wrong = evidence(rows, Scope.FULL_LINEAGE, Map.of("d",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"U",d("0.2"),null)));
        assertTrue(build("P",rows,wrong).statuses().contains("SOURCE_PRODUCT_MISMATCH"));
        assertThrows(IllegalArgumentException.class, () -> build("P",rows,evidence(rows,Scope.FULL_LINEAGE,
                Map.of("d",new IssueEvidence("each",MaterialKind.INTERMEDIATE,null,"U",d("1.2"),null)))));
    }
    @Test void cyclesReturnsSelfConsumptionAndIncompletePositions() {
        List<Issue> rows = List.of(row("a", "P", "A", "S", "-1", "-2", "1"), row("b", "U", "S", "A", "1", "2", "1"));
        Evidence e = evidence(rows, Scope.FULL_LINEAGE, Map.of(
                "a", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, null, "U", d("1"), "original"),
                "b", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, null, "P", d("1"), null)));
        assertTrue(build("P", rows, e).statuses().containsAll(Set.of("CYCLE", "RETURN_REQUIRES_EXPLICIT_NETTING_RULE")));
        List<Issue> self = List.of(row("a", "P", "A", "A", "1", "2", "1"));
        assertTrue(build("P", self, evidence(self, Scope.DIRECT_CONSUMPTION, Map.of())).statuses().contains("SELF_CONSUMPTION"));
        Evidence unknownPosition = evidence(self, Scope.DIRECT_CONSUMPTION, Map.of("a", new IssueEvidence("kg", MaterialKind.RAW, "missing", null, null, null)));
        assertTrue(build("P", self, unknownPosition).statuses().contains("INCOMPLETE_RELATIONSHIP"));
        List<Issue> local = List.of(row("a", "P", "A", "B", "1", "2", "1"), row("b", "P", "A", "C", "1", "2", "1"));
        Evidence cycle = evidence(local, Scope.FULL_LINEAGE, Map.of(
                "a", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, "b", null, null, null),
                "b", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, "a", null, null, null)));
        assertTrue(build("P", local, cycle).statuses().contains("CYCLE"));
    }
    @Test void unresolvedIntermediateIsEligibleOnlyAtCompleteDirectScope() {
        List<Issue> rows = List.of(row("a", "P1", "A", "S", "1", "2", "1"), row("b", "P2", "A", "S", "2", "4", "1"));
        Map<String, IssueEvidence> issues = Map.of("a", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, null, null, null, null),
                "b", new IssueEvidence("kg", MaterialKind.INTERMEDIATE, null, null, null, null));
        Evidence direct = evidence(rows, Scope.DIRECT_CONSUMPTION, issues), full = evidence(rows, Scope.FULL_LINEAGE, issues);
        var adapter = new ProductionTreeFgwAdapter();
        for (Evidence e : List.of(direct, full)) {
            ProductionTree l = build("P1", rows, e), r = build("P2", rows, e);
            Map<String, ProductionComparisonRequest.Embedding> vectors = new HashMap<>();
            for (ProductionTree tree : List.of(l,r)) for (Occurrence n : tree.nodes()) {
                double[] vector = new double[384]; vector[0] = n.index();
                vectors.put(n.occurrenceId(), new ProductionComparisonRequest.Embedding("v1",vector));
            }
            var response = adapter.compare(new ProductionTreeResponse(List.of(l,r),List.of(),"","",null),
                    new ProductionComparisonRequest(null,"P1","P2",vectors,null,null));
            if (e.scope() == Scope.DIRECT_CONSUMPTION) { assertTrue(response.converged()); assertNotNull(response.result()); }
            else { assertTrue(response.statuses().contains("INCOMPLETE_FULL_LINEAGE")); assertNull(response.result()); }
        }
    }
    @Test void adapterAlignmentEligibilityVersionsAndQuantityInvariantObjective() {
        List<Issue> rows = fixed(); Evidence e = evidence(rows, Scope.DIRECT_CONSUMPTION, Map.of());
        ProductionTree l = build("P1", rows, e), r = build("P2", rows, e);
        var response = new ProductionTreeResponse(List.of(l,r), List.of(), "mapping", "scope", null);
        var adapter = new ProductionTreeFgwAdapter();
        var missing = adapter.compare(response, new ProductionComparisonRequest(null,"P1","P2",Map.of(),null,null));
        assertEquals(6, missing.missingVectors().size()); assertNull(missing.result());
        Map<String, ProductionComparisonRequest.Embedding> vectors = new HashMap<>();
        for (ProductionTree tree : List.of(l,r)) for (Occurrence n : tree.nodes()) {
            double[] vector = new double[384]; vector[0] = n.index();
            vectors.put(n.occurrenceId(), new ProductionComparisonRequest.Embedding("v1",vector));
        }
        double[] identity = {1./3,0,0,0,1./3,0,0,0,1./3};
        var compared = adapter.compare(response, new ProductionComparisonRequest(null,"P1","P2",vectors,null,List.of(identity)));
        assertTrue(compared.converged());
        assertEquals(0, compared.result().starts.get(0).costs.fgw(),1e-8);
        assertEquals(l.instanceId(), compared.result().left.instanceId());
        assertEquals("120", compared.result().left.nodes().get(1).quantity());
        assertEquals("30", compared.result().right.nodes().get(1).quantity());
        assertTrue(compared.result().left.nodes().stream().allMatch(n -> n.fifoAllocationIds().isEmpty()));
        assertEquals(l.nodes().get(1).occurrenceId(), compared.result().left.nodes().get(1).occurrenceId());
        assertTrue(adapter.compare(response,new ProductionComparisonRequest(null,"P1","P1",vectors,null,null)).statuses().contains("SAME_PRODUCTION_ID"));
        vectors.put(l.nodes().get(0).occurrenceId(),new ProductionComparisonRequest.Embedding("v1",new double[383]));
        assertTrue(adapter.compare(response,new ProductionComparisonRequest(null,"P1","P2",vectors,null,null)).statuses().contains("INVALID_EMBEDDING_DIMENSION"));
        ProductionTree full = build("P2", rows, evidence(rows, Scope.FULL_LINEAGE, Map.of()));
        assertTrue(adapter.compare(new ProductionTreeResponse(List.of(l,full),List.of(),"","",null),new ProductionComparisonRequest(null,"P1","P2",vectors,null,null)).statuses().contains("INCOMPATIBLE_TOPOLOGY_SCOPE"));
        double[] nonfinite = new double[384]; nonfinite[0] = Double.NaN;
        vectors.put(l.nodes().get(0).occurrenceId(),new ProductionComparisonRequest.Embedding("v2",nonfinite));
        var invalid = adapter.compare(response,new ProductionComparisonRequest(null,"P1","P2",vectors,null,null));
        assertTrue(invalid.statuses().containsAll(Set.of("NONFINITE_EMBEDDING","INCOMPATIBLE_EMBEDDING_VERSIONS")));
    }
}
