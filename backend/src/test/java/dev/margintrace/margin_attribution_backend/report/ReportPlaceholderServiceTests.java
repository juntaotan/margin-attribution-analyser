package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.BlueprintInputField;
import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportPlaceholderServiceTests {

    @Mock
    private ReportPlaceholderConfigRepository configRepository;
    @Mock
    private ReportQueryRunRepository runRepository;
    @Mock
    private ReportBlueprintService reportBlueprintService;
    @Mock
    private ReportQueryService reportQueryService;
    @Mock
    private ReportDocumentStore documentStore;
    @Mock
    private ReportContentControlUpdater contentControlUpdater;

    private ReportPlaceholderService service;

    @BeforeEach
    void setUp() {
        service = new ReportPlaceholderService(
                configRepository,
                runRepository,
                reportBlueprintService,
                reportQueryService,
                documentStore,
                contentControlUpdater);
    }

    @Test
    void generateCreatesConfigWithRevision1AndSuccessfulQueryRun() {
        String tag = "margintrace:revenue";
        String alias = "Sales revenue";
        String prompt = "Duration: January 2026\nCalculate total revenue";
        ReportBlueprint blueprint = sampleBlueprint("SUM(product_total_price)", "currency");
        String sql = "SELECT SUM(product_total_price) AS result FROM sales_order LIMIT 100";
        ReportQueryService.QueryResult queryResult = new ReportQueryService.QueryResult(
                sql, List.of("result"), List.of(Map.of("result", 1250000)), 1);

        when(configRepository.findByDocumentIdAndControlTag("default", tag)).thenReturn(Optional.empty());
        when(reportBlueprintService.generate(prompt)).thenReturn(blueprint);
        when(reportQueryService.generateSql(blueprint)).thenReturn(sql);
        when(reportQueryService.execute(blueprint)).thenReturn(queryResult);

        when(configRepository.save(any(ReportPlaceholderConfigEntity.class))).thenAnswer(invocation -> {
            ReportPlaceholderConfigEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        when(runRepository.save(any(ReportQueryRunEntity.class))).thenAnswer(invocation -> {
            ReportQueryRunEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });

        ReportPlaceholderService.GenerateResult result = service.generate(
                new ReportPlaceholderService.GenerateRequest("default", tag, alias, prompt));

        assertThat(result.configId()).isNotNull();
        assertThat(result.runId()).isNotNull();
        assertThat(result.revision()).isEqualTo(1L);
        assertThat(result.blueprint()).isEqualTo(blueprint);
        assertThat(result.result()).isEqualTo(queryResult);
        assertThat(result.error()).isNull();

        ArgumentCaptor<ReportPlaceholderConfigEntity> configCaptor =
                ArgumentCaptor.forClass(ReportPlaceholderConfigEntity.class);
        verify(configRepository).save(configCaptor.capture());
        ReportPlaceholderConfigEntity savedConfig = configCaptor.getValue();
        assertThat(savedConfig.getControlTag()).isEqualTo(tag);
        assertThat(savedConfig.getControlAlias()).isEqualTo(alias);
        assertThat(savedConfig.getDuration()).isEqualTo("January 2026");
        assertThat(savedConfig.getRevision()).isEqualTo(1L);
        assertThat(savedConfig.getFormat()).isEqualTo("currency");

        ArgumentCaptor<ReportQueryRunEntity> runCaptor =
                ArgumentCaptor.forClass(ReportQueryRunEntity.class);
        verify(runRepository, org.mockito.Mockito.atLeastOnce()).save(runCaptor.capture());
        ReportQueryRunEntity savedRun = runCaptor.getValue();
        assertThat(savedRun.getConfigRevision()).isEqualTo(1L);
        assertThat(savedRun.getStatus()).isEqualTo(ReportQueryRunStatus.SUCCEEDED);
        assertThat(savedRun.getResultSnapshot()).isEqualTo(queryResult);
    }

    @Test
    void generateIncrementsRevisionWhenConfigAlreadyExists() {
        String tag = "margintrace:revenue";
        ReportBlueprint oldBlueprint = sampleBlueprint("SUM(old_val)", "currency");
        ReportPlaceholderConfigEntity existingConfig = new ReportPlaceholderConfigEntity(
                "default", tag, "Old alias", "old prompt", "2025", oldBlueprint, "SELECT 1", "currency");
        existingConfig.setId(UUID.randomUUID());
        assertThat(existingConfig.getRevision()).isEqualTo(1L);

        String newPrompt = "Duration: February 2026\nCalculate updated revenue";
        ReportBlueprint newBlueprint = sampleBlueprint("SUM(new_val)", "currency");
        String sql = "SELECT SUM(new_val) AS result FROM sales_order LIMIT 100";
        ReportQueryService.QueryResult queryResult = new ReportQueryService.QueryResult(
                sql, List.of("result"), List.of(Map.of("result", 900000)), 1);

        when(configRepository.findByDocumentIdAndControlTag("default", tag)).thenReturn(Optional.of(existingConfig));
        when(reportBlueprintService.generate(newPrompt)).thenReturn(newBlueprint);
        when(reportQueryService.generateSql(newBlueprint)).thenReturn(sql);
        when(reportQueryService.execute(newBlueprint)).thenReturn(queryResult);
        when(configRepository.save(any(ReportPlaceholderConfigEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(runRepository.save(any(ReportQueryRunEntity.class))).thenAnswer(i -> {
            ReportQueryRunEntity r = i.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        ReportPlaceholderService.GenerateResult result = service.generate(
                new ReportPlaceholderService.GenerateRequest("default", tag, "New alias", newPrompt));

        assertThat(result.revision()).isEqualTo(2L);
        assertThat(existingConfig.getRevision()).isEqualTo(2L);
        assertThat(existingConfig.getDuration()).isEqualTo("February 2026");
        assertThat(existingConfig.getControlAlias()).isEqualTo("New alias");
    }

    @Test
    void applyRunSuccessfullyWritesResultToDocxAndMarksApplied() throws Exception {
        UUID runId = UUID.randomUUID();
        ReportBlueprint blueprint = sampleBlueprint("SUM(val)", "percentage");
        ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                "default", "tag1", "Margin pct", "prompt", "2026", blueprint, "SELECT", "percentage");
        config.setId(UUID.randomUUID());
        config.setRevision(2L);

        ReportQueryRunEntity run = new ReportQueryRunEntity(config, 2L, "prompt", blueprint, "SELECT");
        run.setId(runId);
        run.markSucceeded(new ReportQueryService.QueryResult(
                "SELECT", List.of("result"), List.of(Map.of("result", "24.5")), 1));

        byte[] fakeDoc = new byte[]{1, 2, 3};
        byte[] updatedDoc = new byte[]{4, 5, 6};

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(documentStore.loadOrCreateDefaultDocument()).thenReturn(fakeDoc);
        when(contentControlUpdater.replaceContentByAlias(fakeDoc, "Margin pct", "24.5%"))
                .thenReturn(new ReportContentControlUpdater.UpdateResult(updatedDoc, 1));
        when(documentStore.currentVersion()).thenReturn(3L);

        ReportPlaceholderService.ApplyResult result = service.applyRun(runId, "Margin pct");

        assertThat(result.documentVersion()).isEqualTo(3L);
        assertThat(result.updatedControls()).isEqualTo(1);
        assertThat(result.appliedValue()).isEqualTo("24.5%");
        assertThat(run.getStatus()).isEqualTo(ReportQueryRunStatus.APPLIED);
        assertThat(run.getAppliedAt()).isNotNull();

        verify(documentStore).saveDefaultDocument(updatedDoc);
        verify(runRepository).save(run);
    }

    @Test
    void applyRunRejectsWhenConfigRevisionHasChangedSinceRun() {
        UUID runId = UUID.randomUUID();
        ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                "default", "tag1", "Revenue", "prompt", "2026", null, "SELECT", "currency");
        config.setId(UUID.randomUUID());
        config.setRevision(3L); // Config has revision 3 now

        ReportQueryRunEntity run = new ReportQueryRunEntity(config, 2L, "old prompt", null, "SELECT"); // Run was on revision 2
        run.setId(runId);
        run.markSucceeded(new ReportQueryService.QueryResult(
                "SELECT", List.of("result"), List.of(Map.of("result", 100)), 1));

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.applyRun(runId, "Revenue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Prompt has changed since this result was generated. Generate again before applying.");
    }

    @Test
    void applyRunRejectsWhenRunFailed() {
        UUID runId = UUID.randomUUID();
        ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                "default", "tag1", "Revenue", "prompt", "2026", null, "SELECT", "currency");
        config.setRevision(1L);

        ReportQueryRunEntity run = new ReportQueryRunEntity(config, 1L, "prompt", null, "SELECT");
        run.setId(runId);
        run.markFailed("DB error");

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.applyRun(runId, "Revenue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only successful query runs can be applied");
    }

    @Test
    void syncPlaceholdersUpdatesControlAliasWithoutChangingRevision() {
        String tag = "margintrace:revenue";
        ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                "default", tag, "Old alias", "prompt", "2026", null, "SELECT", "currency");
        config.setRevision(1L);

        when(configRepository.findByDocumentIdAndControlTag("default", tag)).thenReturn(Optional.of(config));
        when(configRepository.findByDocumentId("default")).thenReturn(List.of(config));

        List<ReportPlaceholderService.PlaceholderConfigDto> dtos = service.syncPlaceholders(
                "default",
                List.of(new ReportPlaceholderService.PlaceholderSyncItem(tag, "Renamed alias in Word")));

        assertThat(config.getControlAlias()).isEqualTo("Renamed alias in Word");
        assertThat(config.getRevision()).isEqualTo(1L); // Revision remains 1
        verify(configRepository).save(config);
        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).alias()).isEqualTo("Renamed alias in Word");
    }

    private ReportBlueprint sampleBlueprint(String formula, String format) {
        return new ReportBlueprint(
                "sales_order",
                "Sales orders",
                List.of("date BETWEEN '2026-01-01' AND '2026-01-31'"),
                List.of(new BlueprintInputField("product_total_price", "Revenue", "Sales line total")),
                formula,
                "Total sales revenue",
                format,
                "Uses recorded sales lines");
    }
}
