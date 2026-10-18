package dev.margintrace.margin_attribution_backend.investigation;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever.Mode;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeIngestionService;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeRepository;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.LocalEmbeddingModel;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Opt-in evaluation with the real local embedding model, and optionally the configured chat model, on the synthetic
 * fixture {@code investigation/evaluation-fixture.json}.
 *
 * <p>Run with {@code -Drag.evaluation=true} and {@code margin.embedding.python/model/revision} pointing at an
 * installed runtime; add
 * {@code -Drag.evaluation.llm=true} to evaluate generated findings against the configured llama.cpp server.
 * The Markdown report is written to {@code rag.evaluation.output} (default {@code target/rag-evaluation.md}).</p>
 *
 * <p>Retrieval metrics per mode: recall@3 per source type (relevant documents retrieved / relevant documents), and
 * mean reciprocal rank of the first relevant document per (anomaly, source type) pair. Generation metrics: citation
 * validity (kept items / generated items) and whether an expected keyword appears in one of the first three
 * hypotheses. Acceptance criteria are listed in {@code docs/algorithms/Anomaly_Investigation_RAG_Design.md}.</p>
 */
@EnabledIfSystemProperty(named = "rag.evaluation", matches = "true")
@SpringBootTest(properties = {
        "margin.investigation.enabled=true",
        "margin.investigation.flyway-locations=classpath:db/investigation-h2",
        "spring.datasource.url=jdbc:h2:mem:investigation-evaluation;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
class RagEvaluationTests {
    private static final double MIN_HYBRID_RECALL = 0.80;
    private static final double MIN_CITATION_VALIDITY = 0.90;
    private static final double MIN_HYPOTHESIS_HIT = 0.60;

    @TestBean EmbeddingStore<TextSegment> knowledgeEmbeddingStore;
    @MockitoBean RootCauseReportService rootCauseReportService;

    @Autowired KnowledgeIngestionService ingestion;
    @Autowired HybridKnowledgeRetriever retriever;
    @Autowired KnowledgeRepository knowledgeRepository;
    @Autowired InvestigationService investigation;
    @Autowired LocalEmbeddingModel embeddingModel;
    @Autowired BillOfMaterialRepository boms;
    @Autowired JdbcClient jdbc;

    static EmbeddingStore<TextSegment> knowledgeEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Test
    void evaluate() throws Exception {
        JsonNode fixture = new ObjectMapper().readTree(getClass().getResourceAsStream("/investigation/evaluation-fixture.json"));
        JsonNode periods = fixture.get("periods");
        LocalDate actualStart = LocalDate.parse(periods.get("actualStart").asString());
        LocalDate actualEnd = LocalDate.parse(periods.get("actualEnd").asString());
        LocalDate comparableStart = LocalDate.parse(periods.get("comparableStart").asString());
        LocalDate comparableEnd = LocalDate.parse(periods.get("comparableEnd").asString());
        LocalDate from = comparableStart.minusDays(90);
        Map<String, String> documentIds = seed(fixture, actualStart);

        StringBuilder report = new StringBuilder("# RAG investigation evaluation\n\n")
                .append("Generated: ").append(ZonedDateTime.now(ZoneId.of("Pacific/Auckland"))).append("\n\n")
                .append("Embedding model: `").append(embeddingModel.signature()).append("`\n\n")
                .append("Fixture: ").append(fixture.get("documents").size()).append(" documents, ")
                .append(fixture.get("anomalies").size()).append(" labelled anomalies, top 3 per source type.\n\n")
                .append("## Retrieval\n\n| Mode | Recall@3 ECN | Recall@3 Quality | Recall@3 Case | Recall@3 overall | MRR |\n|---|---|---|---|---|---|\n");
        Map<Mode, double[]> results = new EnumMap<>(Mode.class);
        StringBuilder misses = new StringBuilder();
        for (Mode mode : List.of(Mode.DENSE_ONLY, Mode.EXACT_ONLY, Mode.HYBRID)) {
            Map<SourceType, int[]> recall = new EnumMap<>(SourceType.class);
            double reciprocalRanks = 0;
            int pairs = 0;
            for (JsonNode anomaly : fixture.get("anomalies")) {
                var retrieval = retriever.retrieve(query(anomaly), anchors(anomaly), from, actualEnd, 3, mode);
                Set<String> relevant = strings(anomaly.get("relevant"));
                for (SourceType type : SourceType.values()) {
                    Set<String> relevantOfType = new HashSet<>();
                    relevant.stream().filter(id -> type(fixture, id) == type).forEach(relevantOfType::add);
                    if (relevantOfType.isEmpty()) continue;
                    List<String> found = retrieval.byType().get(type).stream()
                            .map(chunk -> documentIds.get(chunk.chunk().documentId().toString())).distinct().toList();
                    int[] counts = recall.computeIfAbsent(type, ignored -> new int[2]);
                    counts[0] += (int) found.stream().filter(relevantOfType::contains).count();
                    if (mode == Mode.HYBRID && !found.containsAll(relevantOfType)) {
                        misses.append("| ").append(anomaly.get("id").asString()).append(" | ").append(type).append(" | ")
                                .append(relevantOfType.stream().filter(id -> !found.contains(id)).sorted().toList())
                                .append(" | ").append(found).append(" |\n");
                    }
                    counts[1] += relevantOfType.size();
                    for (int rank = 0; rank < found.size(); rank++) {
                        if (relevantOfType.contains(found.get(rank))) { reciprocalRanks += 1.0 / (rank + 1); break; }
                    }
                    pairs++;
                }
            }
            int hits = recall.values().stream().mapToInt(counts -> counts[0]).sum();
            int total = recall.values().stream().mapToInt(counts -> counts[1]).sum();
            double overall = (double) hits / total;
            double mrr = reciprocalRanks / pairs;
            results.put(mode, new double[]{overall, mrr});
            report.append("| ").append(mode).append(" | ").append(ratio(recall.get(SourceType.ECN)))
                    .append(" | ").append(ratio(recall.get(SourceType.QUALITY))).append(" | ")
                    .append(ratio(recall.get(SourceType.CASE))).append(" | ")
                    .append(String.format(Locale.ROOT, "%.3f (%d/%d)", overall, hits, total)).append(" | ")
                    .append(String.format(Locale.ROOT, "%.3f", mrr)).append(" |\n");
        }
        report.append("\nHybrid misses:\n\n| Anomaly | Type | Missed | Retrieved |\n|---|---|---|---|\n").append(misses);
        double hybrid = results.get(Mode.HYBRID)[0];
        boolean retrievalPass = hybrid >= MIN_HYBRID_RECALL && hybrid >= results.get(Mode.DENSE_ONLY)[0]
                && hybrid >= results.get(Mode.EXACT_ONLY)[0];
        report.append("\nCriterion: hybrid overall recall@3 >= ").append(MIN_HYBRID_RECALL)
                .append(" and not below either single retriever: **").append(retrievalPass ? "PASS" : "FAIL").append("**\n");

        boolean generationPass = true;
        if (Boolean.getBoolean("rag.evaluation.llm")) {
            int kept = 0, generated = 0, hypothesisHits = 0, failures = 0;
            StringBuilder failureDetails = new StringBuilder();
            for (JsonNode anomaly : fixture.get("anomalies")) {
                stubDiagnosis(anomaly);
                var result = investigation.investigate(new RootCauseReportRequest(actualStart, actualEnd,
                        comparableStart, comparableEnd, anomaly.get("material").asString()));
                for (String message : result.messages()) {
                    if (message.startsWith("AI-generated content incomplete") || message.startsWith("The model returned no summary")) {
                        failures++;
                        failureDetails.append("- ").append(anomaly.get("id").asString()).append(": ").append(message).append('\n');
                    }
                }
                int items = result.context().size() + result.hypotheses().size();
                kept += items;
                generated += items + result.removedItemCount();
                Set<String> expected = strings(anomaly.get("expect"));
                if (result.hypotheses().stream().limit(3).anyMatch(hypothesis -> expected.stream()
                        .anyMatch(keyword -> hypothesis.text().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))))) {
                    hypothesisHits++;
                }
            }
            int anomalies = fixture.get("anomalies").size();
            double validity = generated == 0 ? 0 : (double) kept / generated;
            double hitRate = (double) hypothesisHits / anomalies;
            generationPass = failures == 0 && validity >= MIN_CITATION_VALIDITY && hitRate >= MIN_HYPOTHESIS_HIT;
            report.append("\n## Generation\n\n| Metric | Value |\n|---|---|\n")
                    .append(String.format(Locale.ROOT, "| Citation validity | %.3f (%d/%d) |%n", validity, kept, generated))
                    .append(String.format(Locale.ROOT, "| Expected cause in top 3 hypotheses | %.3f (%d/%d) |%n", hitRate, hypothesisHits, anomalies))
                    .append("| Model call failures | ").append(failures).append(" |\n")
                    .append("\nCriterion: no failed model calls, citation validity >= ").append(MIN_CITATION_VALIDITY)
                    .append(", top-3 hit rate >= ").append(MIN_HYPOTHESIS_HIT).append(": **")
                    .append(generationPass ? "PASS" : "FAIL").append("**\n");
            if (!failureDetails.isEmpty()) report.append("\nModel call failures:\n\n").append(failureDetails);
        } else {
            report.append("\n## Generation\n\nNot run (set `-Drag.evaluation.llm=true` with a running llama.cpp server).\n");
        }

        Path output = Path.of(System.getProperty("rag.evaluation.output", "target/rag-evaluation.md"));
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, report);
        System.out.println(report);
        assertThat(retrievalPass).as("retrieval criteria").isTrue();
        assertThat(generationPass).as("generation criteria").isTrue();
    }

    /** Loads material descriptions, warehouse codes, issued orders and documents; returns stored document ID to fixture ID. */
    private Map<String, String> seed(JsonNode fixture, LocalDate actualStart) {
        fixture.get("warehouse").get("materials").properties().forEach(entry -> jdbc.sql(
                        "INSERT INTO material_master (material_no, material_name, specification) VALUES (:no, :name, :spec)")
                .param("no", entry.getKey()).param("name", entry.getValue().get(0).asString())
                .param("spec", entry.getValue().get(1).asString()).update());
        int bom = 0;
        for (JsonNode line : fixture.get("warehouse").get("bom")) {
            boms.save(BillOfMaterial.of("BOM-" + (++bom), line.get(0).asString(), line.get(1).asString(), BigDecimal.ONE));
        }
        int movement = 0;
        for (JsonNode anomaly : fixture.get("anomalies")) {
            for (JsonNode order : anomaly.get("orders")) {
                jdbc.sql("""
                        INSERT INTO inventory_usage (date, movement_no, product_no, product_num, order_no, material_no,
                                                     material_num, material_total_cost)
                        VALUES (:date, :movement, :product, 1, :order, :material, 1, 1)""")
                        .param("date", actualStart).param("movement", "MV-" + (++movement))
                        .param("product", anomaly.get("products").get(0).asString()).param("order", order.asString())
                        .param("material", anomaly.get("material").asString()).update();
            }
        }
        Map<String, String> ids = new HashMap<>();
        for (JsonNode document : fixture.get("documents")) {
            UUID id = UUID.randomUUID();
            ingestion.add(id, SourceType.valueOf(document.get("type").asString()), document.get("title").asString(),
                    LocalDate.parse(document.get("date").asString()), document.get("id").asString() + ".txt",
                    "knowledge/" + id + "/fixture.txt", List.of(document.get("title").asString() + ". " + document.get("text").asString()));
            ids.put(id.toString(), document.get("id").asString());
        }
        return ids;
    }

    private void stubDiagnosis(JsonNode anomaly) {
        String category = anomaly.get("category").asString();
        String certainty = Set.of("MATERIAL_REPLACEMENT", "UNDETERMINED").contains(category) ? "Needs verification" : "Confirmed by records";
        when(rootCauseReportService.diagnosis(any())).thenReturn(new RootCauseReportService.Diagnosis(category,
                anomaly.get("label").asString(), certainty, new ArrayList<>(strings(anomaly.get("facts"))), null, null,
                strings(anomaly.get("products"))));
    }

    private String query(JsonNode anomaly) {
        Set<String> described = new LinkedHashSet<>(strings(anomaly.get("products")));
        described.add(anomaly.get("material").asString());
        return InvestigationService.retrievalQuery(anomaly.get("label").asString(), anomaly.get("material").asString(),
                new ArrayList<>(strings(anomaly.get("products"))).stream().sorted().toList(),
                knowledgeRepository.describe(described));
    }

    private static Set<String> anchors(JsonNode anomaly) {
        Set<String> anchors = new LinkedHashSet<>(List.of(anomaly.get("material").asString()));
        anchors.addAll(strings(anomaly.get("products")));
        anchors.addAll(strings(anomaly.get("orders")));
        return anchors;
    }

    private static SourceType type(JsonNode fixture, String documentId) {
        for (JsonNode document : fixture.get("documents")) {
            if (document.get("id").asString().equals(documentId)) return SourceType.valueOf(document.get("type").asString());
        }
        throw new IllegalArgumentException("Unknown fixture document " + documentId);
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    private static String ratio(int[] counts) {
        return counts == null ? "n/a" : String.format(Locale.ROOT, "%.3f (%d/%d)", (double) counts[0] / counts[1], counts[0], counts[1]);
    }
}
