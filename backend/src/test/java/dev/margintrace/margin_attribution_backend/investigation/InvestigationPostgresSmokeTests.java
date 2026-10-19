package dev.margintrace.margin_attribution_backend.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileStorage;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.HybridKnowledgeRetriever.Mode;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeIngestionService;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.LocalEmbeddingModel;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.SourceType;
import io.minio.MinioClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Opt-in check of the real PostgreSQL path: main and feature Flyway migrations, the pgvector extension and
 * {@code PgVectorEmbeddingStore} metadata filters. Requires an empty database with pgvector available, for example the
 * project's {@code postgres} image. Run with {@code -Dinvestigation.postgres.url=jdbc:postgresql://host:port/db}
 * plus {@code -Dinvestigation.postgres.username} and {@code -Dinvestigation.postgres.password}.
 */
@EnabledIfSystemProperty(named = "investigation.postgres.url", matches = ".+")
@SpringBootTest(
        properties = {
            "margin.investigation.enabled=true",
            "spring.flyway.enabled=true",
            "spring.flyway.locations=classpath:db/migration",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.datasource.url=${investigation.postgres.url}",
            "spring.datasource.username=${investigation.postgres.username}",
            "spring.datasource.password=${investigation.postgres.password}"
        })
class InvestigationPostgresSmokeTests {
    @TestBean
    LocalEmbeddingModel localEmbeddingModel;

    @MockitoBean
    RawFileStorage rawFileStorage;

    @MockitoBean
    MinioClient minioClient;

    @Autowired
    KnowledgeIngestionService ingestion;

    @Autowired
    HybridKnowledgeRetriever retriever;

    @Autowired
    JdbcClient jdbc;

    static LocalEmbeddingModel localEmbeddingModel() {
        return new LocalEmbeddingModel(FakeEmbeddingProvider::new);
    }

    @Test
    void migratesStoresFiltersAndDeletesOnPostgres() throws Exception {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM investigation_schema_history WHERE success")
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM pg_extension WHERE extname = 'vector'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        var inside = add(
                SourceType.ECN, "Inside window", LocalDate.of(2026, 2, 1), "Coating thickness increase for MAT-STEEL.");
        var outside = add(
                SourceType.ECN,
                "Outside window",
                LocalDate.of(2025, 1, 1),
                "Coating thickness increase for MAT-STEEL.");
        add(SourceType.CASE, "Old case", LocalDate.of(2024, 1, 1), "Coating thickness overuse resolved.");

        var dense = retriever.retrieve(
                "coating thickness increase",
                Set.of(),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                3,
                Mode.DENSE_ONLY);
        var exact = retriever.retrieve(
                "unused", Set.of("MAT-STEEL"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), 3, Mode.EXACT_ONLY);

        assertThat(dense.messages()).isEmpty();
        assertThat(dense.byType().get(SourceType.ECN))
                .extracting(found -> found.chunk().title())
                .containsExactly("Inside window");
        assertThat(dense.byType().get(SourceType.CASE))
                .extracting(found -> found.chunk().title())
                .containsExactly("Old case");
        assertThat(exact.byType().get(SourceType.ECN))
                .extracting(found -> found.chunk().title())
                .containsExactly("Inside window");

        ingestion.delete(inside.id());
        ingestion.delete(outside.id());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM knowledge_embedding WHERE metadata->>'sourceType' = 'ECN'")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    private dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeRepository.KnowledgeDocument
            add(SourceType type, String title, LocalDate date, String text) {
        UUID id = UUID.randomUUID();
        return ingestion.add(id, type, title, date, title + ".txt", "knowledge/" + id + "/doc.txt", List.of(text));
    }
}
