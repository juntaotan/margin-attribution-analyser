package dev.margintrace.margin_attribution_backend.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.KnowledgeController;
import dev.margintrace.margin_attribution_backend.investigation.knowledge.LocalEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** With the default configuration the feature contributes no beans, endpoints or schema. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:investigation-off;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
class InvestigationDisabledTests {
    @Autowired
    ApplicationContext context;

    @Autowired
    RequestMappingHandlerMapping mappings;

    @Autowired
    JdbcClient jdbc;

    @Test
    void featureIsAbsentByDefault() {
        assertThat(context.getBeanNamesForType(InvestigationService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(KnowledgeController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LocalEmbeddingModel.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EmbeddingStore.class)).isEmpty();
        assertThat(mappings.getHandlerMethods().keySet().toString()).doesNotContain("/api/v1/investigation");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_NAME) IN "
                                + "('INVESTIGATION_SCHEMA_HISTORY', 'KNOWLEDGE_DOCUMENT')")
                        .query(Integer.class)
                        .single())
                .isZero();
    }
}
