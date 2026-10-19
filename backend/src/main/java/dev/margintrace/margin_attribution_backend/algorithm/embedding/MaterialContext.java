package dev.margintrace.margin_attribution_backend.algorithm.embedding;

import java.math.BigDecimal;
import java.util.List;

/** Twelve-field MATERIAL_CONTEXT_V1 template with occurrence/source identities held only in provenance. */
public record MaterialContext(String code, Evidence evidence, List<Neighbour> upstream, List<Neighbour> downstream) {
    public record Description(String name, String specification, String category, List<String> references) {}

    public record Evidence(
            Description description,
            String sourceKind,
            List<String> suppliers,
            BigDecimal unitCost,
            String currency,
            String unit,
            String costScope,
            String valuationBasis,
            List<String> references,
            boolean conflicting) {}

    public record Neighbour(String occurrenceId, String code, String name, String specification) {}

    /** Whitespace collapses; labelled values escape separators, decimals use plain canonical text. */
    public String canonical() {
        return "MATERIAL_CONTEXT_V1/1\ncode=" + clean(code) + "\nname="
                + clean(evidence.description().name())
                + "\nspecification=" + clean(evidence.description().specification()) + "\nsupplier="
                + clean(evidence.sourceKind()) + ":"
                + evidence.suppliers().stream()
                        .map(MaterialContext::clean)
                        .sorted()
                        .toList()
                + "\ncategory=" + clean(evidence.description().category()) + "\nunit cost="
                + evidence.unitCost().stripTrailingZeros().toPlainString() + " " + clean(evidence.currency()) + "/"
                + clean(evidence.unit()) + ";" + clean(evidence.costScope()) + ";" + clean(evidence.valuationBasis())
                + "\nupstream codes=" + values(upstream, 0) + "\nupstream names=" + values(upstream, 1)
                + "\nupstream specifications=" + values(upstream, 2) + "\ndownstream codes=" + values(downstream, 0)
                + "\ndownstream names=" + values(downstream, 1) + "\ndownstream specifications="
                + values(downstream, 2);
    }

    private static List<String> values(List<Neighbour> nodes, int field) {
        return nodes.stream()
                .map(n -> clean(field == 0 ? n.code() : field == 1 ? n.name() : n.specification()))
                .toList();
    }

    private static String clean(String text) {
        return text.strip()
                .replaceAll("\\s+", " ")
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace(",", "\\,")
                .replace("=", "\\=");
    }
}
