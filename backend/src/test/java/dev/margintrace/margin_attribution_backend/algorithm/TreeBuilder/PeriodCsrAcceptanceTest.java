package dev.margintrace.margin_attribution_backend.algorithm.TreeBuilder;

import static org.junit.jupiter.api.Assertions.*;

import dev.margintrace.margin_attribution_backend.algorithm.BOMFrontier.BomGraphPreparer;
import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.Issue;
import dev.margintrace.margin_attribution_backend.algorithm.model.CsrGraph;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PeriodCsrAcceptanceTest {
    static final LocalDate DATE = LocalDate.of(2026, 9, 15);

    static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static Issue row(String id, String receiver, String material, String quantity, String amount, LocalDate date) {
        return new Issue(
                id,
                date,
                "document:" + id,
                receiver + "-P",
                receiver,
                material,
                d(quantity),
                d(amount),
                BigDecimal.TEN,
                null);
    }

    static List<Issue> rows() {
        return List.of(
                row("L1", "A", "B", "10", "100", DATE),
                row("L2", "A", "C", "10", "80", DATE),
                row("L3", "B", "D", "20", "100", DATE),
                row("L4", "C", "E", "10", "80", DATE));
    }

    static PeriodGraphProvider.Snapshot load(List<Issue> rows) {
        return new RecordPeriodGraphProvider(new PeriodRecords(
                        List.of(new PeriodRecords.Sale("sale", DATE, "A", BigDecimal.TEN, d("180"))), rows, true))
                .load(DATE, DATE);
    }

    static void valid(CsrGraph g) {
        BomGraphPreparer.validate(g);
        assertEquals(g.nodes().length + 1, g.offset().length);
    }

    static Set<String> edges(CsrGraph g) {
        var result = new TreeSet<String>();
        for (int i = 0; i < g.nodes().length; i++)
            for (int e = g.offset()[i]; e < g.offset()[i + 1]; e++)
                result.add(g.nodes()[i].inventoryId() + ">" + g.nodes()[g.successors()[e]].inventoryId());
        return result;
    }

    @Test
    void C01_fixedArraysAndRepeatedOutputs() {
        var s = load(rows());
        var g = s.graph();
        valid(g);
        assertEquals(
                List.of("A", "B", "C", "D", "E", "__COGS__"),
                Arrays.stream(g.nodes()).map(n -> n.inventoryId()).toList());
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 5}, g.offset());
        assertArrayEquals(new int[] {5, 0, 0, 1, 2}, g.successors());
        assertEquals(0, BigDecimal.TEN.compareTo(g.nodes()[0].quantity()));
        assertNull(g.nodes()[0].cost());
        assertEquals(0, d("100").compareTo(g.nodes()[1].cost()));
        assertEquals(0, d("80").compareTo(g.nodes()[2].cost()));
        assertEquals(0, d("20").compareTo(g.nodes()[3].quantity()));
        assertEquals(0, d("100").compareTo(g.nodes()[3].cost()));
        assertEquals(0, d("180").compareTo(g.nodes()[5].cost()));
        assertEquals(Set.of("B>A", "C>A", "D>B", "E>C", "A>__COGS__"), edges(g));
        assertEquals(rows(), s.records().issues());
        assertEquals(3, s.sources().get("L3").materialPosition());
        assertEquals(1, s.sources().get("L3").receivingPosition());
    }

    @Test
    void C02_C03_emptyAndMissingProduction() {
        var empty = new RecordPeriodGraphProvider(new PeriodRecords(List.of(), List.of(), true))
                .load(DATE, DATE)
                .graph();
        valid(empty);
        assertEquals(0, empty.nodes().length);
        assertArrayEquals(new int[] {0}, empty.offset());
        assertArrayEquals(new int[] {}, empty.successors());
        var sold = load(List.of()).graph();
        valid(sold);
        assertArrayEquals(new int[] {0, 1, 1}, sold.offset());
        assertArrayEquals(new int[] {1}, sold.successors());
        assertEquals(
                List.of("A", "__COGS__"),
                Arrays.stream(sold.nodes()).map(n -> n.inventoryId()).toList());
    }

    @Test
    void C04_C05_C06_splitSharedMaterialAndPermutation() {
        var split = new ArrayList<>(rows());
        split.remove(2);
        split.add(row("L3a", "B", "D", "8", "40", DATE));
        split.add(row("L3b", "B", "D", "12", "60", DATE));
        var g = load(split).graph();
        valid(g);
        assertEquals(0, d("20").compareTo(g.nodes()[3].quantity()));
        assertEquals(0, d("100").compareTo(g.nodes()[3].cost()));
        assertEquals(edges(load(rows()).graph()), edges(g));
        assertEquals(5, load(split).records().issues().size());
        split.add(row("L5", "C", "D", "3", "15", DATE));
        var shared = load(split).graph();
        valid(shared);
        assertEquals(0, d("23").compareTo(shared.nodes()[3].quantity()));
        assertEquals(0, d("115").compareTo(shared.nodes()[3].cost()));
        assertTrue(edges(shared).containsAll(Set.of("D>B", "D>C")));
        Collections.reverse(split);
        var shuffled = load(split).graph();
        valid(shuffled);
        assertEquals(edges(shared), edges(shuffled));
        assertArrayEquals(shared.nodes(), shuffled.nodes());
        assertArrayEquals(shared.offset(), shuffled.offset());
    }

    @Test
    void C07_C08_boundariesAndExactDecimalTotals() {
        var rows = List.of(
                row("before", "A", "B", "1", "1", DATE.minusDays(1)),
                row("on1", "A", "B", "1", "0.1", DATE),
                row("on2", "A", "B", "1", "0.2", DATE),
                row("after", "A", "B", "1", "1", DATE.plusDays(1)));
        var g = load(rows).graph();
        valid(g);
        assertEquals(0, d("2").compareTo(g.nodes()[1].quantity()));
        assertEquals(0, d("0.3").compareTo(g.nodes()[1].cost()));
        g = load(List.of(
                        row("huge", "A", "B", "1", "9007199254740993.01", DATE),
                        row("tiny", "A", "B", "1", "0.02", DATE)))
                .graph();
        assertEquals(0, d("9007199254740993.03").compareTo(g.nodes()[1].cost()));
    }

    @Test
    void C09_sharedIntermediateNotMultiplied() {
        var data = new ArrayList<>(rows());
        data.add(row("shared", "C", "B", "1", "10", DATE));
        var g = load(data).graph();
        valid(g);
        assertEquals(0, d("100").compareTo(g.nodes()[3].cost()));
        assertEquals(0, d("20").compareTo(g.nodes()[3].quantity()));
    }

    @Test
    void C10_invalidDatesAndIncompleteEvidence() {
        var provider = new RecordPeriodGraphProvider(new PeriodRecords(List.of(), List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> provider.load(null, DATE));
        assertThrows(IllegalArgumentException.class, () -> provider.load(DATE, DATE.minusDays(1)));
        var missing = load(List.of(row("missing", "A", "B", "1", null, DATE)));
        assertNull(missing.graph().nodes()[1].cost());
        assertTrue(missing.diagnostics().contains("MISSING_ISSUE_COST:missing"));
        var self = load(List.of(row("self", "A", "A", "1", "1", DATE)));
        assertTrue(self.diagnostics().contains("SELF_CONSUMPTION:self"));
        var absent = load(List.of(row("absent", "A", null, "1", "1", DATE)));
        assertTrue(absent.diagnostics().contains("MISSING_MATERIAL_ID:absent"));
        assertThrows(IllegalArgumentException.class, () -> load(List.of(row("quantity", "A", "B", null, "1", DATE))));
        var cycle = load(List.of(row("a", "A", "B", "1", "1", DATE), row("b", "B", "A", "1", "1", DATE)))
                .graph();
        assertThrows(IllegalArgumentException.class, () -> BomGraphPreparer.validate(cycle));
    }
}
