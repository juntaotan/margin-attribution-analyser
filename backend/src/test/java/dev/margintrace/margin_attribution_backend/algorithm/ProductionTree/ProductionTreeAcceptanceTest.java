package dev.margintrace.margin_attribution_backend.algorithm.ProductionTree;

import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeFixtures.graph;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTreeTwoPassTest.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Exact additional T04/T05/T07 instances; construction receives original rows through both lookup ports. */
class ProductionTreeAcceptanceTest {
    @Test
    void T04_directAndDeepBranchesCompleteAt45() {
        var rows = new ArrayList<>(fixed());
        rows.set(2, issue("L_SA", "P_A", "A", "S", "2", "30", "2", "45"));
        rows.add(issue("L_TA", "P_A", "A", "T", "1", "5", "2", "45"));
        var e = evidence(rows);
        var allocations = new ArrayList<>(e.processingAllocations());
        allocations.add(allocation("alloc-T", "source-A", "P_A", List.of("L_TA"), "0", "0"));
        var g = graph("fixture", Map.of("P_A", "A"), "A>S", "S>R", "A>T");
        var result = build("P_A", rows, costs(e, e.processingSources(), allocations), g);
        assertEquals("35", result.recordedMaterialAmount());
        assertEquals("45", result.calculatedTotalCost());
        assertTrue(result.costComplete());
        assertEquals(Set.of("R", "T"), new HashSet<>(result.topDownScope().terminalNodeIds()));
        assertEquals(2, result.topDownScope().paths().size());
        Collections.reverse(rows);
        assertEquals(
                result.nodes(),
                build("P_A", rows, costs(e, e.processingSources(), allocations), g)
                        .nodes());
    }

    @Test
    void T05_sharedTerminalRetainsTwoOccurrencesAndTotal100() {
        var rows = List.of(
                issue("S1-A", "P_A", "A", "S1", "10", "40", "10", "100"),
                issue("S2-A", "P_A", "A", "S2", "10", "60", "10", "100"),
                issue("R-S1", "P_S1", "S1", "R", "10", "40", "10", "40"),
                issue("R-S2", "P_S2", "S2", "R", "10", "60", "10", "60"));
        var issues = new TreeMap<String, IssueEvidence>();
        var outputs = new TreeMap<String, OutputEvidence>();
        var sources = new TreeMap<String, ProcessingSource>();
        var allocations = new ArrayList<ProcessingAllocation>();
        for (var row : rows) {
            boolean intermediate = row.materialId().startsWith("S");
            issues.put(
                    row.lineId(),
                    new IssueEvidence(
                            intermediate ? "each" : "kg",
                            intermediate ? MaterialKind.INTERMEDIATE : MaterialKind.RAW,
                            null,
                            intermediate ? "P_" + row.materialId() : null,
                            intermediate ? d("1") : null,
                            null));
            outputs.put(row.productionId(), totals());
            sources.put(row.productionId(), pool(row.productionId(), row.productionId(), "0", "0"));
            allocations.add(allocation(
                    "allocation:" + row.lineId(),
                    row.productionId(),
                    row.productionId(),
                    List.of(row.lineId()),
                    "0",
                    "0"));
        }
        var e = new Evidence(
                "fixture",
                Scope.FULL_LINEAGE,
                outputs.keySet(),
                issues,
                outputs,
                "NZD",
                "manufacturing",
                "manufacturing",
                List.copyOf(sources.values()),
                allocations);
        var result = build("P_A", rows, e, graph("fixture", Map.of("P_A", "A"), "A>S1", "A>S2", "S1>R", "S2>R"));
        assertEquals("100", result.recordedMaterialAmount());
        assertEquals("100", result.calculatedTotalCost());
        assertTrue(result.costComplete());
        assertTrue(result.lineageComplete());
        assertEquals(
                2,
                result.nodes().stream().filter(n -> n.materialId().equals("R")).count());
        assertEquals(
                2,
                result.nodes().stream()
                        .filter(n -> n.materialId().equals("R"))
                        .map(Occurrence::occurrenceId)
                        .distinct()
                        .count());
    }

    @Test
    void T07_explicitAllocationsYield115AndOverdrawIsRejected() {
        var rows = List.of(
                issue("B-A", "P_A", "A", "B", "1", "60", "1", "115"),
                issue("C-A", "P_A", "A", "C", "1", "40", "1", "115"));
        var base = evidence(rows);
        var sources = List.of(pool("root-pool", "P_A", "10", "5"));
        var allocations = List.of(
                allocation("alloc-B", "root-pool", "P_A", List.of("B-A"), "6", "3"),
                allocation("alloc-C", "root-pool", "P_A", List.of("C-A"), "4", "2"));
        var e = costs(base, sources, allocations);
        var g = graph("fixture", Map.of("P_A", "A"), "A>B", "A>C");
        var result = build("P_A", rows, e, g);
        assertEquals("100", result.recordedMaterialAmount());
        assertEquals("10", result.laborCost());
        assertEquals("5", result.overheadCost());
        assertEquals("115", result.calculatedTotalCost());
        assertTrue(result.costComplete());
        assertThrows(
                IllegalArgumentException.class,
                () -> build(
                        "P_A",
                        rows,
                        costs(
                                base,
                                sources,
                                List.of(
                                        allocation("B", "root-pool", "P_A", List.of("B-A"), "10", "5"),
                                        allocation("C", "root-pool", "P_A", List.of("C-A"), "10", "5"))),
                        g));
    }
}
