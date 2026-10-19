package dev.margintrace.margin_attribution_backend.investigation;

import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Investigation report returned by {@code POST /api/v1/investigation}.
 *
 * <p>{@code facts}, {@code metrics}, {@code scope}, {@code timeline} and {@code sources} are computed or retrieved by
 * the backend. {@code context}, {@code hypotheses} and {@code summary} are model-generated and are null or empty when
 * the model is unavailable; context and hypotheses have passed citation validation. Decimal values are plain strings
 * to avoid binary floating-point conversion.</p>
 */
public record InvestigationReport(
        String inventoryId,
        Periods periods,
        Classification classification,
        String summary,
        List<Metric> metrics,
        List<Fact> facts,
        Scope scope,
        List<TimelineEvent> timeline,
        List<ContextItem> context,
        List<Hypothesis> hypotheses,
        List<Source> sources,
        List<String> messages,
        int removedItemCount) {

    public record Periods(
            LocalDate actualStart, LocalDate actualEnd, LocalDate comparableStart, LocalDate comparableEnd) {}

    public record Classification(String category, String label, String certainty) {}

    /** A value is null when the material is absent from that period; {@code change} is actual minus comparison. */
    public record Metric(String label, String comparison, String actual, String change) {}

    public record Fact(String id, String text) {}

    public record Scope(String material, List<String> products, List<Order> orders) {}

    /** A production order that issued the material; {@code period} is ACTUAL or COMPARISON. */
    public record Order(String orderNo, LocalDate date, String product, String period) {}

    /** {@code position} is BEFORE, COMPARISON, BETWEEN, ACTUAL or AFTER relative to the two periods. */
    public record TimelineEvent(
            LocalDate date, String sourceId, SourceType sourceType, String title, String position) {}

    public record ContextItem(SourceType sourceType, String text, List<String> citations) {}

    public record Hypothesis(String text, List<String> citations, String nextCheck, String certainty) {}

    public record Source(
            String id,
            UUID documentId,
            SourceType sourceType,
            String title,
            LocalDate effectiveDate,
            String excerpt,
            HybridKnowledgeRetriever.Method retrievedBy) {}
}
