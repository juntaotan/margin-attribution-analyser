package dev.margintrace.margin_attribution_backend.investigation;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.margintrace.margin_attribution_backend.algorithm.model.Node;
import dev.margintrace.margin_attribution_backend.analysis.dto.RootCauseReportRequest;
import dev.margintrace.margin_attribution_backend.analysis.service.RootCauseReportService;
import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileStorage;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever.Mode;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeIngestionService;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeRepository;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.LocalEmbeddingModel;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import dev.margintrace.margin_attribution_backend.warehouse.model.BillOfMaterial;
import dev.margintrace.margin_attribution_backend.warehouse.repository.BillOfMaterialRepository;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Knowledge base, retrieval and report wiring with the feature enabled, on H2 with an in-memory vector store. */
@SpringBootTest(properties = {
        "margin.investigation.enabled=true",
        "margin.investigation.flyway-locations=classpath:db/investigation-h2",
        "spring.datasource.url=jdbc:h2:mem:investigation-on;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"})
class InvestigationIntegrationTests {
    private static final AtomicBoolean EMBEDDING_AVAILABLE = new AtomicBoolean(true);
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @TestBean EmbeddingStore<TextSegment> knowledgeEmbeddingStore;
    @TestBean LocalEmbeddingModel localEmbeddingModel;
    @MockitoBean RawFileStorage rawFileStorage;
    @MockitoBean MinioClient minioClient;
    @MockitoBean RootCauseReportService rootCauseReportService;
    @MockitoBean InvestigationAssistant assistant;

    @Autowired KnowledgeIngestionService ingestion;
    @Autowired KnowledgeRepository repository;
    @Autowired HybridKnowledgeRetriever retriever;
    @Autowired InvestigationService investigation;
    @Autowired BillOfMaterialRepository boms;
    @Autowired JdbcClient jdbc;
    @Autowired RequestMappingHandlerMapping mappings;

    static EmbeddingStore<TextSegment> knowledgeEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    static LocalEmbeddingModel localEmbeddingModel() {
        return new LocalEmbeddingModel(() -> {
            if (!EMBEDDING_AVAILABLE.get()) throw new IllegalStateException("Embedding runtime is not installed");
            return new FakeEmbeddingProvider();
        });
    }

    @BeforeEach
    void clean() {
        EMBEDDING_AVAILABLE.set(true);
        localEmbeddingModel.close();
        repository.findDocuments().forEach(document -> repository.deleteDocument(document.id()));
        knowledgeEmbeddingStore.removeAll();
        boms.deleteAll();
        boms.save(BillOfMaterial.of("BOM-1", "PROD-A", "MAT-10023", new BigDecimal("2")));
    }

    @Test
    void featureSchemaAndEndpointsExist() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) = 'INVESTIGATION_SCHEMA_HISTORY'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(mappings.getHandlerMethods().keySet().toString())
                .contains("/api/v1/investigation", "/api/v1/investigation/knowledge/documents");
    }

    @Test
    void ingestionStoresBoundedChunksKnownCodesVectorsAndRawFile() throws Exception {
        String sentence = "ECN-2026-014 increases the coating thickness of MAT-10023 used in PROD-A. ";
        var file = new MockMultipartFile("file", "coating change.txt", "text/plain",
                sentence.repeat(60).getBytes(StandardCharsets.UTF_8));

        var document = ingestion.ingest(file, SourceType.ECN, "Coating change", LocalDate.of(2026, 2, 10));

        List<String> chunkIds = repository.findChunkIds(document.id());
        assertThat(chunkIds).hasSizeGreaterThan(1);
        FakeEmbeddingProvider tokens = new FakeEmbeddingProvider();
        assertThat(repository.findChunks(chunkIds)).allSatisfy(chunk -> assertThat(tokens.tokenCount(chunk.text())).isLessThanOrEqualTo(200));
        assertThat(jdbc.sql("SELECT DISTINCT code FROM knowledge_chunk_code").query(String.class).list())
                .containsExactlyInAnyOrder("MAT-10023", "PROD-A");
        assertThat(document.embeddingSignature()).isEqualTo(FakeEmbeddingProvider.METADATA.signature());
        assertThat(document.objectKey()).isEqualTo("knowledge/" + document.id() + "/coating_change.txt");
        verify(rawFileStorage).store(file, document.objectKey());
        assertThat(knowledgeEmbeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(localEmbeddingModel.embed("coating thickness").content()).maxResults(100).build())
                .matches()).hasSize(chunkIds.size());
    }

    @Test
    void exactRetrievalFindsAChunkThatOnlyMentionsTheCode() {
        add(SourceType.QUALITY, "Lot record", LocalDate.of(2026, 3, 2), "Supplier lot 7781 for MAT-10023 accepted.");
        add(SourceType.QUALITY, "Unrelated", LocalDate.of(2026, 3, 3), "Coating thickness drifted above specification.");

        var exact = retriever.retrieve("coating thickness drifted", Set.of("MAT-10023"), FROM, TO, 3, Mode.EXACT_ONLY);
        var hybrid = retriever.retrieve("coating thickness drifted", Set.of("MAT-10023"), FROM, TO, 3, Mode.HYBRID);

        assertThat(exact.byType().get(SourceType.QUALITY)).singleElement()
                .satisfies(found -> assertThat(found.chunk().title()).isEqualTo("Lot record"));
        assertThat(hybrid.byType().get(SourceType.QUALITY))
                .extracting(found -> found.chunk().title(), HybridKnowledgeRetriever.RetrievedChunk::retrievedBy)
                .contains(org.assertj.core.groups.Tuple.tuple("Lot record", HybridKnowledgeRetriever.Method.BOTH),
                        org.assertj.core.groups.Tuple.tuple("Unrelated", HybridKnowledgeRetriever.Method.DENSE));
    }

    @Test
    void dateWindowAppliesToChangesAndQualityButNotToCases() {
        add(SourceType.ECN, "Old change", LocalDate.of(2025, 6, 1), "Coating change for MAT-10023.");
        add(SourceType.ECN, "Current change", LocalDate.of(2026, 2, 1), "Coating change for MAT-10023.");
        add(SourceType.CASE, "Old case", LocalDate.of(2024, 5, 1), "Coating overuse on MAT-10023 resolved.");

        var result = retriever.retrieve("coating change", Set.of("MAT-10023"), FROM, TO, 3, Mode.HYBRID);

        assertThat(result.byType().get(SourceType.ECN)).extracting(found -> found.chunk().title()).containsExactly("Current change");
        assertThat(result.byType().get(SourceType.CASE)).extracting(found -> found.chunk().title()).containsExactly("Old case");
        assertThat(result.byType().get(SourceType.QUALITY)).isEmpty();
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void unavailableEmbeddingRuntimeFallsBackToExactMatching() {
        add(SourceType.ECN, "Current change", LocalDate.of(2026, 2, 1), "Coating change for MAT-10023.");
        EMBEDDING_AVAILABLE.set(false);
        localEmbeddingModel.close();

        var result = retriever.retrieve("coating change", Set.of("MAT-10023"), FROM, TO, 3, Mode.HYBRID);

        assertThat(result.messages()).singleElement().asString().contains("exact code matching only");
        assertThat(result.byType().get(SourceType.ECN)).singleElement()
                .extracting(HybridKnowledgeRetriever.RetrievedChunk::retrievedBy).isEqualTo(HybridKnowledgeRetriever.Method.EXACT);
    }

    @Test
    void deleteRemovesVectorsRowsAndRawFile() throws Exception {
        var document = add(SourceType.ECN, "Current change", LocalDate.of(2026, 2, 1), "Coating change for MAT-10023.");

        ingestion.delete(document.id());

        assertThat(repository.findDocument(document.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM knowledge_chunk_code").query(Integer.class).single()).isZero();
        assertThat(knowledgeEmbeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(localEmbeddingModel.embed("coating").content()).maxResults(10).build()).matches()).isEmpty();
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void reportCitesRetrievedSources() {
        add(SourceType.ECN, "Coating change", LocalDate.of(2026, 2, 1), "ECN raises coating thickness for MAT-10023.");
        when(rootCauseReportService.diagnosis(any())).thenReturn(new RootCauseReportService.Diagnosis("OVERUSE",
                "Material usage exceeds BOM plan", "Confirmed by records", List.of("Usage rose 20%."),
                new Node("MAT-10023", new BigDecimal("180"), null), new Node("MAT-10023", new BigDecimal("150"), null),
                Set.of("PROD-A")));
        when(assistant.findings(anyString(), anyString(), contains("[S1] engineering change notices, 2026-02-01")))
                .thenReturn(new InvestigationAssistant.Findings(
                        List.of(new InvestigationAssistant.Context("The ECN raised coating thickness.", List.of("S1"))),
                        List.of(new InvestigationAssistant.Hypothesis("Thicker coating", List.of("F1", "S1"), "Measure coating"))));
        when(assistant.summary(anyString(), anyString(), anyString())).thenReturn("Usage rose after the coating ECN.");

        var report = investigation.investigate(new RootCauseReportRequest(LocalDate.of(2026, 3, 1), TO, FROM,
                LocalDate.of(2026, 1, 31), "MAT-10023"));

        assertThat(report.sources()).singleElement().satisfies(source -> {
            assertThat(source.title()).isEqualTo("Coating change");
            assertThat(source.retrievedBy()).isEqualTo(HybridKnowledgeRetriever.Method.BOTH);
        });
        assertThat(report.context()).hasSize(1);
        assertThat(report.hypotheses()).hasSize(1);
        assertThat(report.removedItemCount()).isZero();
        assertThat(report.summary()).isEqualTo("Usage rose after the coating ECN.");
    }

    private KnowledgeRepository.KnowledgeDocument add(SourceType type, String title, LocalDate date, String text) {
        UUID id = UUID.randomUUID();
        return ingestion.add(id, type, title, date, title + ".txt", "knowledge/" + id + "/doc.txt", List.of(text));
    }
}
