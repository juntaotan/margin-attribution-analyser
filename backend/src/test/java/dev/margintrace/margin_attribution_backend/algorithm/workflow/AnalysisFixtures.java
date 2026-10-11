package dev.margintrace.margin_attribution_backend.algorithm.workflow;

import dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.*;
import dev.margintrace.margin_attribution_backend.algorithm.port.*;
import dev.margintrace.margin_attribution_backend.algorithm.embedding.*;
import dev.margintrace.margin_attribution_backend.algorithm.FGW.FgwEngine;
import dev.margintrace.margin_attribution_backend.algorithm.execution.ExecutionPolicy;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static dev.margintrace.margin_attribution_backend.algorithm.ProductionTree.ProductionTree.*;

/** Original control-enclosure records from Analysis_Fixture.md, not prepared graphs or trees. */
public final class AnalysisFixtures {
    public static final String NAMESPACE = "FULL_FLOW_V1";
    public static final LocalDate AUGUST = LocalDate.of(2026, 8, 15), SEPTEMBER = LocalDate.of(2026, 9, 15);
    public static BigDecimal d(String value) { return new BigDecimal(value); }
    public static PeriodRecords records() {
        var rows = new ArrayList<Issue>(); rows.addAll(rows("P0", AUGUST)); rows.addAll(rows("P1", SEPTEMBER));
        return new PeriodRecords(List.of(new PeriodRecords.Sale("sale-P0", AUGUST, "A", BigDecimal.TEN, d("240")),
                new PeriodRecords.Sale("sale-P1", SEPTEMBER, "A", BigDecimal.TEN, d("270"))), rows, true);
    }
    public static List<Issue> rows(String p, LocalDate date) {
        boolean actual = p.equals("P1");
        return List.of(issue(p, date, "A", "S1", "10", actual ? "150" : "130"),
                issue(p, date, "A", "S2", "10", actual ? "100" : "90"),
                issue(p + "-S1", date, "S1", "R1", "20", actual ? "120" : "100"),
                issue(p + "-S1", date, "S1", "R2", "100", "20"),
                issue(p + "-S2", date, "S2", "R3", "5", "50"),
                issue(p + "-S2", date, "S2", actual ? "R5" : "R4", "5", actual ? "40" : "30"));
    }
    private static Issue issue(String p, LocalDate date, String product, String material, String quantity, String amount) {
        String cost = product.equals("A") ? (p.equals("P1") ? "270" : "240")
                : product.equals("S1") ? (p.startsWith("P1") ? "150" : "130") : (p.startsWith("P1") ? "100" : "90");
        return new Issue(p + ":" + material, date, "document:" + p + ":" + material, p, product, material,
                d(quantity), d(amount), BigDecimal.TEN, d(cost));
    }
    public static Evidence evidence(PeriodRecords records) {
        var issues = new TreeMap<String, IssueEvidence>(); var outputs = new TreeMap<String, OutputEvidence>();
        var pools = new TreeMap<String, ProcessingSource>(); var allocations = new ArrayList<ProcessingAllocation>();
        for (var row : records.issues()) {
            boolean intermediate = row.materialId().startsWith("S");
            issues.put(row.lineId(), new IssueEvidence(unit(row.materialId()), intermediate ? MaterialKind.INTERMEDIATE : MaterialKind.RAW,
                    null, intermediate ? row.productionId() + "-" + row.materialId() : null, intermediate ? BigDecimal.ONE : null, null));
            outputs.put(row.productionId(), new OutputEvidence(OutputInterpretation.RECEIPTS, OutputInterpretation.RECEIPTS,
                    List.of(new Receipt("receipt:" + row.productionId(), BigDecimal.TEN, row.outputCost())), "each", true, "NZD", "manufacturing"));
            boolean root = row.productId().equals("A");
            String source = "pool:" + row.productionId();
            pools.put(source, new ProcessingSource(source, row.productionId(), "NZD", "manufacturing", d(root ? "12" : "6"), d(root ? "8" : "4")));
            allocations.add(new ProcessingAllocation("allocation:" + row.lineId(), source, row.productionId(), null,
                    List.of(row.lineId()), "NZD", "manufacturing", "explicit half-pool relationship allocation", d(root ? "6" : "3"), d(root ? "4" : "2")));
        }
        return new Evidence(NAMESPACE, Scope.FULL_LINEAGE, Set.copyOf(outputs.keySet()), issues, outputs, "NZD", "manufacturing", "manufacturing",
                List.copyOf(pools.values()), allocations);
    }
    public static AnalysisCommand command() {
        var config = new FgwEngine.Config(); config.captureTrace = true;
        return new AnalysisCommand(SEPTEMBER.withDayOfMonth(1), SEPTEMBER.withDayOfMonth(30), AUGUST.withDayOfMonth(1), AUGUST.withDayOfMonth(30),
                d("10"), d("25"), ExecutionPolicy.cpu(), EmbeddingSettings.cpu(),
                new AnalysisCommand.Comparison(evidence(records()), true, Map.of(), Map.of(), config));
    }
    public static String unit(String material) { return Set.of("R1", "R3", "R4", "R5").contains(material) ? "kg" : "each"; }
    public static MaterialContext.Evidence context(ProductionTree tree, Occurrence node) {
        String[] description = switch (node.materialId()) {
            case "A" -> new String[]{"Industrial control enclosure", "400 x 300 x 200 mm; assembled enclosure", "Finished product"};
            case "S1" -> new String[]{"Steel enclosure assembly", "Folded steel enclosure; 2 mm wall", "Intermediate assembly"};
            case "S2" -> new String[]{"Surface finishing assembly", "Coating package for one enclosure", "Intermediate assembly"};
            case "R1" -> new String[]{"Cold-rolled steel sheet", "SPCC; 2 mm thickness", "Sheet metal"};
            case "R2" -> new String[]{"Hexagon bolt", "M6 x 20 mm; zinc plated; grade 8.8", "Fastener"};
            case "R3" -> new String[]{"Epoxy primer", "Two-component epoxy; grey", "Primer"};
            case "R4" -> new String[]{"Standard topcoat", "Polyester; grey RAL 7035", "Topcoat"};
            case "R5" -> new String[]{"Corrosion-resistant topcoat", "Polyurethane; grey RAL 7035; corrosion resistant", "Topcoat"};
            default -> throw new IllegalArgumentException("Unknown fixture material");
        };
        boolean internal = !node.materialId().startsWith("R");
        String supplier = switch (node.materialId()) {
            case "R1" -> "Steel Supplier Alpha"; case "R2" -> "Fastener Supplier Beta";
            case "R3", "R4" -> "Coating Supplier Gamma"; case "R5" -> "Coating Supplier Delta"; default -> "";
        };
        String amount = node.index() == tree.rootIndex() ? tree.calculatedTotalCost() : internal ? node.sourceTotalCost() : node.amount();
        String quantity = node.index() == tree.rootIndex() ? tree.outputQuantity() : internal ? node.sourceOutputQuantity() : node.quantity();
        var references = new ArrayList<>(node.issueLineIds());
        if (internal) references.add("receipt:" + (node.index() == tree.rootIndex() ? tree.productionId() : node.sourceProductionId()));
        return new MaterialContext.Evidence(new MaterialContext.Description(description[0], description[1], description[2],
                List.of("material-master:" + node.materialId())), internal ? "INTERNAL" : "PURCHASED", internal ? List.of() : List.of(supplier),
                d(amount).divide(d(quantity)), "NZD", unit(node.materialId()), "manufacturing", internal ? "confirmed total/output" : "issue amount/quantity",
                List.copyOf(references), false);
    }
    public static class RecordingProvider implements EmbeddingProvider {
        public final List<Input> seen = new ArrayList<>(); public boolean closed;
        public Metadata metadata() { return new Metadata("recording-test", "fixture-revision", "MEAN", "L2", "FP32", "CPU", "TEST_ONLY", "cpu", null); }
        public int maximumTokens() { return 10000; }
        public int tokenCount(String text) { return text.length(); }
        public Map<String, double[]> encode(List<Input> inputs) {
            seen.addAll(inputs); var result = new LinkedHashMap<String, double[]>();
            var reversed = new ArrayList<>(inputs); Collections.reverse(reversed);
            for (var input : reversed) { var vector = new double[384]; vector[0] = 1; vector[1] = input.text().hashCode() / (double) Integer.MAX_VALUE; result.put(input.hash(), vector); }
            return result;
        }
        public void close() { closed = true; }
    }
}
