package dev.margintrace.margin_attribution_backend.investigation;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiConnectionSettings;
import dev.margintrace.margin_attribution_backend.analysis.ai.AiRuntimeManager;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.LocalEmbeddingModel;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Wires the optional anomaly investigation feature.
 *
 * <p>Every bean in the {@code investigation} package exists only when {@code margin.investigation.enabled=true}.
 * When it is disabled the feature has no endpoints, beans or schema. The feature owns its schema through a separate
 * Flyway history table ({@code investigation_schema_history}), so the main migration history is untouched and
 * databases without pgvector keep working while the feature is off.</p>
 *
 * <p>Model access reuses the user's AI connection settings and the local embedding runtime configuration
 * ({@code margin.embedding.*}); no additional hosts or credentials are introduced.</p>
 */
@Configuration
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class InvestigationConfiguration {
    public static final String ENABLED = "margin.investigation.enabled";
    /** Vector size of the supported local sentence encoder and of {@code knowledge_embedding.embedding}. */
    public static final int DIMENSION = 384;

    /**
     * Applies the feature migrations after the application's own migrations, when those are enabled.
     *
     * @param locations Flyway locations; tests point this at an H2 copy without pgvector
     * @return a marker that other beans depend on so the schema exists before use
     */
    @Bean
    InvestigationSchema investigationSchema(
            DataSource dataSource,
            ObjectProvider<FlywayMigrationInitializer> mainMigrations,
            @Value("${margin.investigation.flyway-locations:classpath:db/investigation}") String[] locations) {
        mainMigrations.ifAvailable(initializer -> { });
        Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                .table("investigation_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        return new InvestigationSchema();
    }

    /** pgvector store over the migrated {@code knowledge_embedding} table; never creates tables or extensions. */
    @Bean
    EmbeddingStore<TextSegment> knowledgeEmbeddingStore(DataSource dataSource, InvestigationSchema schema) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("knowledge_embedding")
                .dimension(DIMENSION)
                .createTable(false)
                .skipCreateVectorExtension(true)
                .useIndex(false)
                .build();
    }

    /** Local sentence encoder, started lazily so the application starts without the runtime installed. */
    @Bean(destroyMethod = "close")
    LocalEmbeddingModel localEmbeddingModel(
            @Value("${margin.embedding.python:}") String python,
            @Value("${margin.embedding.model:}") String model,
            @Value("${margin.embedding.revision:}") String revision,
            @Value("${margin.embedding.timeout-seconds:60}") long timeoutSeconds) {
        return new LocalEmbeddingModel(() -> LocalEmbeddingModel.startPython(python, model, revision, timeoutSeconds));
    }

    /**
     * Assistant built per call from the current AI settings, so changes made in Settings apply immediately
     * without modifying the shared AI runtime.
     */
    @Bean
    InvestigationAssistant investigationAssistant(AiRuntimeManager runtimeManager) {
        return new InvestigationAssistant() {
            @Override
            public Findings findings(String anomaly, String facts, String sources) {
                return create(runtimeManager.current().settings()).findings(anomaly, facts, sources);
            }

            @Override
            public String summary(String anomaly, String facts, String findings) {
                return create(runtimeManager.current().settings()).summary(anomaly, facts, findings);
            }
        };
    }

    private static InvestigationAssistant create(AiConnectionSettings settings) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(settings.baseUrl() + "/v1")
                .apiKey("no-key")
                .modelName(settings.model())
                .temperature(0.1)
                .maxTokens(1500)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(0)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .strictJsonSchema(true)
                .build();
        return AiServices.create(InvestigationAssistant.class, model);
    }

    /** Marker for a migrated feature schema. */
    public record InvestigationSchema() { }
}
