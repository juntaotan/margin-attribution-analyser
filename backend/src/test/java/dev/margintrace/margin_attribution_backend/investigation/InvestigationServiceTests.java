package dev.margintrace.margin_attribution_backend.investigation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiErrorMapper;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationAssistant.Context;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationAssistant.Findings;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationReport.*;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeRepository;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import dev.margintrace.margin_attribution_backend.warehouse.repository.InventoryUsageRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InvestigationServiceTests {
    private static final Periods PERIODS = new Periods(
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    private static final List<Fact> FACTS = List.of(new Fact("F1", "fact one"), new Fact("F2", "fact two"));
    private static final List<Source> SOURCES = List.of(
            source("S1", SourceType.ECN, LocalDate.of(2026, 2, 20)),
            source("S2", SourceType.QUALITY, LocalDate.of(2026, 3, 5)));

    private final RootCauseReportService rootCause = mock(RootCauseReportService.class);
    private final InventoryUsageRepository usage = mock(InventoryUsageRepository.class);
    private final HybridKnowledgeRetriever retriever = mock(HybridKnowledgeRetriever.class);
    private final KnowledgeRepository knowledge = mock(KnowledgeRepository.class);
    private final InvestigationAssistant assistant = mock(InvestigationAssistant.class);
    private final AiErrorMapper errors = mock(AiErrorMapper.class);
    private final InvestigationService service =
            new InvestigationService(rootCause, usage, retriever, knowledge, assistant, errors, 90);

    @Test
    void contextNeedsAKnownSourceCitation() {
        var result = InvestigationService.validate(
                new Findings(
                        List.of(
                                new Context("valid", List.of("[S1]", "F1")),
                                new Context("facts only", List.of("F1")),
                                new Context("unknown source", List.of("S1", "S9")),
                                new Context("no citations", List.of()),
                                new Context(" ", List.of("S2"))),
                        List.of()),
                FACTS,
                SOURCES);

        assertThat(result.context()).extracting(ContextItem::text).containsExactly("valid");
        assertThat(result.context().get(0).citations()).containsExactly("S1", "F1");
        assertThat(result.context().get(0).sourceType()).isEqualTo(SourceType.ECN);
        assertThat(result.removed()).isEqualTo(4);
    }

    @Test
    void hypothesesNeedKnownCitationsAndANextCheck() {
        var result = InvestigationService.validate(
                new Findings(
                        null,
                        List.of(
                                new InvestigationAssistant.Hypothesis(
                                        "valid", List.of("f2", "S2"), "Check lot records"),
                                new InvestigationAssistant.Hypothesis("no next check", List.of("F1"), " "),
                                new InvestigationAssistant.Hypothesis("unknown fact", List.of("F7"), "Check"),
                                new InvestigationAssistant.Hypothesis("uncited", null, "Check"))),
                FACTS,
                SOURCES);

        assertThat(result.hypotheses()).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.text()).isEqualTo("valid");
            assertThat(hypothesis.citations()).containsExactly("F2", "S2");
            assertThat(hypothesis.certainty()).isEqualTo("Needs verification");
        });
        assertThat(result.removed()).isEqualTo(3);
    }

    @Test
    void metricsKeepExactDecimals() {
        var metrics = InvestigationService.metrics(
                new Node("MAT", new BigDecimal("180.000000"), new BigDecimal("3600.10")),
                new Node("MAT", new BigDecimal("150"), new BigDecimal("3000.00")));

        assertThat(metrics)
                .containsExactly(
                        new Metric("Quantity", "150", "180", "30"),
                        new Metric("Total cost", "3000", "3600.1", "600.1"),
                        new Metric("Unit cost", "20", "20.00055555555556", "0.00055555555556"));
    }

    @Test
    void metricsOfAMaterialAbsentFromOnePeriodHaveNoChange() {
        var metrics = InvestigationService.metrics(new Node("MAT", new BigDecimal("5"), null), null);

        assertThat(metrics).containsExactly(new Metric("Quantity", null, "5", null));
    }

    @Test
    void timelinePositionsRespectPeriodBoundaries() {
        assertThat(InvestigationService.position(LocalDate.of(2025, 12, 31), PERIODS))
                .isEqualTo("BEFORE");
        assertThat(InvestigationService.position(LocalDate.of(2026, 1, 1), PERIODS))
                .isEqualTo("COMPARISON");
        assertThat(InvestigationService.position(LocalDate.of(2026, 1, 31), PERIODS))
                .isEqualTo("COMPARISON");
        assertThat(InvestigationService.position(LocalDate.of(2026, 2, 1), PERIODS))
                .isEqualTo("BETWEEN");
        assertThat(InvestigationService.position(LocalDate.of(2026, 3, 1), PERIODS))
                .isEqualTo("ACTUAL");
        assertThat(InvestigationService.position(LocalDate.of(2026, 3, 31), PERIODS))
                .isEqualTo("ACTUAL");
        assertThat(InvestigationService.position(LocalDate.of(2026, 4, 1), PERIODS))
                .isEqualTo("AFTER");
    }

    @Test
    void reportKeepsFactsAndSummarisesOnlyValidatedFindings() {
        stubDiagnosisAndRetrieval(true);
        when(assistant.findings(anyString(), anyString(), anyString()))
                .thenReturn(new Findings(
                        List.of(
                                new Context("ECN changed the coating", List.of("S1")),
                                new Context("Invented claim", List.of("S99"))),
                        List.of(new InvestigationAssistant.Hypothesis(
                                "Coating change raised usage",
                                List.of("F1", "S1"),
                                "Compare coating thickness before and after the ECN"))));
        when(assistant.summary(anyString(), anyString(), anyString())).thenReturn("  Usage rose after the ECN.  ");

        InvestigationReport report = service.investigate(request());

        assertThat(report.facts()).extracting(Fact::text).containsExactly("Material MAT rose.", "BOM plan exceeded.");
        assertThat(report.classification().category()).isEqualTo("OVERUSE");
        assertThat(report.sources()).extracting(Source::id).containsExactly("S1");
        assertThat(report.context()).extracting(ContextItem::text).containsExactly("ECN changed the coating");
        assertThat(report.hypotheses()).hasSize(1);
        assertThat(report.removedItemCount()).isEqualTo(1);
        assertThat(report.summary()).isEqualTo("Usage rose after the ECN.");
        assertThat(report.timeline())
                .singleElement()
                .extracting(TimelineEvent::position)
                .isEqualTo("BETWEEN");
        assertThat(report.messages())
                .contains(
                        "No quality records found between 2025-10-03 and 2026-03-31.",
                        "No similar historical cases found.");

        ArgumentCaptor<String> findings = ArgumentCaptor.forClass(String.class);
        verify(assistant).summary(anyString(), anyString(), findings.capture());
        assertThat(findings.getValue()).contains("ECN changed the coating").doesNotContain("Invented claim");
    }

    @Test
    void unavailableModelStillReturnsComputedParts() {
        stubDiagnosisAndRetrieval(true);
        RuntimeException failure = new RuntimeException("connection refused");
        when(assistant.findings(anyString(), anyString(), anyString())).thenThrow(failure);
        when(errors.message(failure)).thenReturn("Cannot connect to llama.cpp at 127.0.0.1:8081");

        InvestigationReport report = service.investigate(request());

        assertThat(report.facts()).hasSize(2);
        assertThat(report.metrics()).isNotEmpty();
        assertThat(report.sources()).hasSize(1);
        assertThat(report.summary()).isNull();
        assertThat(report.context()).isEmpty();
        assertThat(report.hypotheses()).isEmpty();
        assertThat(report.messages())
                .contains("AI-generated content incomplete: Cannot connect to llama.cpp at 127.0.0.1:8081");
        verify(assistant, never()).summary(anyString(), anyString(), anyString());
    }

    @Test
    void emptyKnowledgeBaseIsReported() {
        stubDiagnosisAndRetrieval(false);
        when(assistant.findings(anyString(), anyString(), anyString())).thenReturn(new Findings(List.of(), List.of()));
        when(assistant.summary(anyString(), anyString(), anyString())).thenReturn("Summary.");

        InvestigationReport report = service.investigate(request());

        assertThat(report.sources()).isEmpty();
        assertThat(report.messages())
                .contains("No engineering change notices found between 2025-10-03 and 2026-03-31.");
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        verify(assistant).findings(anyString(), anyString(), sources.capture());
        assertThat(sources.getValue()).isEqualTo("(none found)");
    }

    private void stubDiagnosisAndRetrieval(boolean withEcn) {
        when(rootCause.diagnosis(any()))
                .thenReturn(new RootCauseReportService.Diagnosis(
                        "OVERUSE",
                        "Material usage exceeds BOM plan",
                        "Confirmed by records",
                        List.of("Material MAT rose.", "BOM plan exceeded."),
                        new Node("MAT", new BigDecimal("180"), new BigDecimal("3600")),
                        new Node("MAT", new BigDecimal("150"), new BigDecimal("3000")),
                        Set.of("PRODUCT")));
        when(usage.findAllByProductNoAndDateBetweenOrderByDateAscIdAsc(anyString(), any(), any()))
                .thenReturn(List.of());
        Map<SourceType, List<HybridKnowledgeRetriever.RetrievedChunk>> byType = new LinkedHashMap<>();
        var chunk = new KnowledgeRepository.ChunkView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                SourceType.ECN,
                "Coating change",
                LocalDate.of(2026, 2, 15),
                "The coating thickness was increased.");
        byType.put(
                SourceType.ECN,
                withEcn
                        ? List.of(new HybridKnowledgeRetriever.RetrievedChunk(
                                chunk, HybridKnowledgeRetriever.Method.BOTH))
                        : List.of());
        byType.put(SourceType.QUALITY, List.of());
        byType.put(SourceType.CASE, List.of());
        when(retriever.retrieve(
                        anyString(),
                        anyCollection(),
                        eq(LocalDate.of(2025, 10, 3)),
                        eq(LocalDate.of(2026, 3, 31)),
                        eq(3),
                        eq(HybridKnowledgeRetriever.Mode.HYBRID)))
                .thenReturn(new HybridKnowledgeRetriever.Retrieval(byType, List.of()));
    }

    private static RootCauseReportRequest request() {
        return new RootCauseReportRequest(
                PERIODS.actualStart(), PERIODS.actualEnd(), PERIODS.comparableStart(), PERIODS.comparableEnd(), "MAT");
    }

    private static Source source(String id, SourceType type, LocalDate date) {
        return new Source(
                id, UUID.randomUUID(), type, "title " + id, date, "excerpt", HybridKnowledgeRetriever.Method.DENSE);
    }
}
