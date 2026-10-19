package dev.margintrace.margin_attribution_backend.investigation;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationReport.*;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeRepository;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import dev.margintrace.margin_attribution_backend.warehouse.model.InventoryUsage;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Builds the anomaly investigation report.
 *
 * <p>Steps: reuse the deterministic root-cause diagnosis; compute metrics, scope (products and production orders)
 * and the date window; describe the items from the material master for the similarity query; retrieve sources per type with {@link HybridKnowledgeRetriever}; ask the model for cited
 * context and hypotheses; remove any item whose citations are missing or unknown; then ask for a summary of the
 * validated findings only. Facts and numbers never come from the model. When retrieval or the model is
 * unavailable, the computed parts are still returned and {@code messages} says what is missing.</p>
 */
@Service
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class InvestigationService {
    static final int SOURCES_PER_TYPE = 3;
    private static final int EXCERPT_CHARACTERS = 1200;

    private final RootCauseReportService rootCauseReportService;
    private final InventoryUsageRepository inventoryUsageRepository;
    private final HybridKnowledgeRetriever retriever;
    private final KnowledgeRepository knowledgeRepository;
    private final InvestigationAssistant assistant;
    private final AiErrorMapper aiErrorMapper;
    private final int windowDays;

    public InvestigationService(
            RootCauseReportService rootCauseReportService,
            InventoryUsageRepository inventoryUsageRepository,
            HybridKnowledgeRetriever retriever,
            KnowledgeRepository knowledgeRepository,
            InvestigationAssistant assistant,
            AiErrorMapper aiErrorMapper,
            @Value("${margin.investigation.window-days:90}") int windowDays) {
        if (windowDays < 0) throw new IllegalArgumentException("margin.investigation.window-days must not be negative");
        this.rootCauseReportService = rootCauseReportService;
        this.inventoryUsageRepository = inventoryUsageRepository;
        this.retriever = retriever;
        this.knowledgeRepository = knowledgeRepository;
        this.assistant = assistant;
        this.aiErrorMapper = aiErrorMapper;
        this.windowDays = windowDays;
    }

    /**
     * @param request selected material and both periods, as for the root-cause report
     * @throws IllegalArgumentException for an invalid request or a material absent from both periods
     */
    public InvestigationReport investigate(RootCauseReportRequest request) {
        RootCauseReportService.Diagnosis diagnosis = rootCauseReportService.diagnosis(request);
        String material = request.inventoryId();
        Periods periods = new Periods(
                request.actualStartDate(),
                request.actualEndDate(),
                request.comparableStartDate(),
                request.comparableEndDate());
        List<String> messages = new ArrayList<>();

        List<Fact> facts = new ArrayList<>();
        for (String line : diagnosis.evidence()) facts.add(new Fact("F" + (facts.size() + 1), line));
        List<String> products = diagnosis.products().stream().sorted().toList();
        List<Order> orders = new ArrayList<>(
                orders(material, products, periods.comparableStart(), periods.comparableEnd(), "COMPARISON"));
        orders.addAll(orders(material, products, periods.actualStart(), periods.actualEnd(), "ACTUAL"));

        Set<String> anchors = new LinkedHashSet<>(List.of(material));
        anchors.addAll(products);
        orders.forEach(order -> anchors.add(order.orderNo()));
        LocalDate from = min(periods.actualStart(), periods.comparableStart()).minusDays(windowDays);
        LocalDate to = max(periods.actualEnd(), periods.comparableEnd());
        Set<String> described = new LinkedHashSet<>(products);
        described.add(material);
        var retrieval = retriever.retrieve(
                retrievalQuery(diagnosis.label(), material, products, knowledgeRepository.describe(described)),
                anchors,
                from,
                to,
                SOURCES_PER_TYPE,
                HybridKnowledgeRetriever.Mode.HYBRID);
        messages.addAll(retrieval.messages());

        List<Source> sources = new ArrayList<>();
        retrieval.byType().forEach((type, chunks) -> {
            if (chunks.isEmpty())
                messages.add(
                        type == SourceType.CASE
                                ? "No similar historical cases found."
                                : "No " + label(type) + " found between " + from + " and " + to + ".");
            for (var found : chunks) {
                var chunk = found.chunk();
                String text = chunk.text();
                sources.add(new Source(
                        "S" + (sources.size() + 1),
                        chunk.documentId(),
                        chunk.sourceType(),
                        chunk.title(),
                        chunk.effectiveDate(),
                        text.length() > EXCERPT_CHARACTERS ? text.substring(0, EXCERPT_CHARACTERS) + "…" : text,
                        found.retrievedBy()));
            }
        });

        String anomaly = diagnosis.label() + " (" + diagnosis.certainty() + ") for material " + material;
        String factText = String.join(
                "\n",
                facts.stream().map(fact -> "[" + fact.id() + "] " + fact.text()).toList());
        List<ContextItem> context = List.of();
        List<Hypothesis> hypotheses = List.of();
        String summary = null;
        int removed = 0;
        try {
            InvestigationAssistant.Findings findings = assistant.findings(anomaly, factText, sourceText(sources));
            Validated validated = validate(findings, facts, sources);
            context = validated.context();
            hypotheses = validated.hypotheses();
            removed = validated.removed();
            if (removed > 0) messages.add(removed + " generated item(s) removed for missing or unknown citations.");
            summary = assistant.summary(anomaly, factText, findingText(context, hypotheses));
            summary = summary == null || summary.isBlank() ? null : summary.strip();
            if (summary == null) messages.add("The model returned no summary.");
        } catch (RuntimeException exception) {
            messages.add("AI-generated content incomplete: " + aiErrorMapper.message(exception));
        }

        return new InvestigationReport(
                material,
                periods,
                new Classification(diagnosis.category(), diagnosis.label(), diagnosis.certainty()),
                summary,
                metrics(diagnosis.actualNode(), diagnosis.comparableNode()),
                facts,
                new Scope(material, products, orders),
                timeline(sources, periods),
                context,
                hypotheses,
                sources,
                messages,
                removed);
    }

    /**
     * Dense retrieval query: the classification plus the material-master descriptions of the material and products.
     * Codes and figures are left out because exact retrieval handles identifiers, and in a sentence encoder they make
     * code-heavy documents look similar regardless of meaning. An item without a description falls back to its code.
     */
    static String retrievalQuery(
            String label, String material, List<String> products, Map<String, String> descriptions) {
        Function<String, String> named = code -> descriptions.getOrDefault(code, code);
        return label + ". Material: " + named.apply(material) + ". Used in: "
                + String.join("; ", products.stream().map(named).toList()) + ".";
    }

    /** Validated findings and the number of generated items removed. */
    record Validated(List<ContextItem> context, List<Hypothesis> hypotheses, int removed) {}

    /**
     * Keeps context items citing only known IDs and at least one source, and hypotheses citing only known IDs with a
     * next check. Invalid items are dropped, not repaired.
     */
    static Validated validate(InvestigationAssistant.Findings findings, List<Fact> facts, List<Source> sources) {
        Map<String, Source> sourceById = new HashMap<>();
        sources.forEach(source -> sourceById.put(source.id(), source));
        Set<String> known = new HashSet<>(sourceById.keySet());
        facts.forEach(fact -> known.add(fact.id()));
        int removed = 0;
        List<ContextItem> context = new ArrayList<>();
        for (var item : findings == null || findings.context() == null
                ? List.<InvestigationAssistant.Context>of()
                : findings.context()) {
            List<String> citations = normalise(item == null ? null : item.citations());
            Optional<Source> cited = citations.stream()
                    .map(sourceById::get)
                    .filter(Objects::nonNull)
                    .findFirst();
            if (item == null
                    || blank(item.text())
                    || citations.isEmpty()
                    || !known.containsAll(citations)
                    || cited.isEmpty()) {
                removed++;
                continue;
            }
            context.add(new ContextItem(cited.get().sourceType(), item.text().strip(), citations));
        }
        List<Hypothesis> hypotheses = new ArrayList<>();
        for (var item : findings == null || findings.hypotheses() == null
                ? List.<InvestigationAssistant.Hypothesis>of()
                : findings.hypotheses()) {
            List<String> citations = normalise(item == null ? null : item.citations());
            if (item == null
                    || blank(item.text())
                    || blank(item.nextCheck())
                    || citations.isEmpty()
                    || !known.containsAll(citations)) {
                removed++;
                continue;
            }
            hypotheses.add(new Hypothesis(
                    item.text().strip(), citations, item.nextCheck().strip(), "Needs verification"));
        }
        return new Validated(context, hypotheses, removed);
    }

    private static List<String> normalise(List<String> citations) {
        if (citations == null) return List.of();
        return citations.stream()
                .filter(Objects::nonNull)
                .map(citation -> citation.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT))
                .filter(citation -> !citation.isEmpty())
                .distinct()
                .toList();
    }

    private List<Order> orders(String material, List<String> products, LocalDate start, LocalDate end, String period) {
        Map<String, Order> orders = new LinkedHashMap<>();
        for (String product : products) {
            for (InventoryUsage usage :
                    inventoryUsageRepository.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(product, start, end)) {
                if (material.equals(usage.getMaterialNo())) {
                    orders.putIfAbsent(
                            usage.getOrderNo(), new Order(usage.getOrderNo(), usage.getDate(), product, period));
                }
            }
        }
        return new ArrayList<>(orders.values());
    }

    static List<Metric> metrics(Node actual, Node comparable) {
        List<Metric> metrics = new ArrayList<>();
        add(metrics, "Quantity", comparable, actual, Node::quantity);
        add(metrics, "Total cost", comparable, actual, Node::cost);
        add(
                metrics,
                "Unit cost",
                comparable,
                actual,
                node -> node.cost() == null
                                || node.quantity() == null
                                || node.quantity().signum() <= 0
                        ? null
                        : node.cost().divide(node.quantity(), MathContext.DECIMAL64));
        return metrics;
    }

    private static void add(
            List<Metric> metrics, String label, Node comparable, Node actual, Function<Node, BigDecimal> value) {
        BigDecimal before = comparable == null ? null : value.apply(comparable);
        BigDecimal after = actual == null ? null : value.apply(actual);
        if (before == null && after == null) return;
        metrics.add(new Metric(
                label,
                plain(before),
                plain(after),
                before == null || after == null ? null : plain(after.subtract(before))));
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static List<TimelineEvent> timeline(List<Source> sources, Periods periods) {
        Set<UUID> seen = new HashSet<>();
        List<TimelineEvent> events = new ArrayList<>();
        for (Source source : sources) {
            if (!seen.add(source.documentId())) continue;
            events.add(new TimelineEvent(
                    source.effectiveDate(),
                    source.id(),
                    source.sourceType(),
                    source.title(),
                    position(source.effectiveDate(), periods)));
        }
        events.sort(Comparator.comparing(TimelineEvent::date));
        return events;
    }

    static String position(LocalDate date, Periods periods) {
        if (!date.isBefore(periods.actualStart()) && !date.isAfter(periods.actualEnd())) return "ACTUAL";
        if (!date.isBefore(periods.comparableStart()) && !date.isAfter(periods.comparableEnd())) return "COMPARISON";
        if (date.isBefore(min(periods.actualStart(), periods.comparableStart()))) return "BEFORE";
        if (date.isAfter(max(periods.actualEnd(), periods.comparableEnd()))) return "AFTER";
        return "BETWEEN";
    }

    private static String sourceText(List<Source> sources) {
        if (sources.isEmpty()) return "(none found)";
        StringBuilder text = new StringBuilder();
        for (Source source : sources) {
            text.append('[')
                    .append(source.id())
                    .append("] ")
                    .append(label(source.sourceType()))
                    .append(", ")
                    .append(source.effectiveDate())
                    .append(", \"")
                    .append(source.title())
                    .append("\": ")
                    .append(source.excerpt())
                    .append("\n\n");
        }
        return text.toString().strip();
    }

    private static String findingText(List<ContextItem> context, List<Hypothesis> hypotheses) {
        StringBuilder text = new StringBuilder();
        context.forEach(item -> text.append("Context: ")
                .append(item.text())
                .append(' ')
                .append(item.citations())
                .append('\n'));
        hypotheses.forEach(item -> text.append("Hypothesis: ")
                .append(item.text())
                .append(' ')
                .append(item.citations())
                .append("; next check: ")
                .append(item.nextCheck())
                .append('\n'));
        return text.isEmpty() ? "(no validated findings)" : text.toString().strip();
    }

    private static String label(SourceType type) {
        return switch (type) {
            case ECN -> "engineering change notices";
            case QUALITY -> "quality records";
            case CASE -> "historical cases";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
