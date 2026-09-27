package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReportPlaceholderService {

    private static final Pattern DURATION_PATTERN = Pattern.compile("(?i)^Duration:\\s*([^\r\n]*)");

    private final ReportPlaceholderConfigRepository configRepository;
    private final ReportQueryRunRepository runRepository;
    private final ReportBlueprintService reportBlueprintService;
    private final ReportQueryService reportQueryService;
    private final ReportDocumentStore documentStore;
    private final ReportContentControlUpdater contentControlUpdater;

    @Transactional(readOnly = true)
    public List<PlaceholderConfigDto> findConfigurations(String documentId, Collection<String> tags) {
        String docId = normalizeDocumentId(documentId);
        List<ReportPlaceholderConfigEntity> entities;
        if (tags == null || tags.isEmpty()) {
            entities = configRepository.findByDocumentId(docId);
        } else {
            entities = configRepository.findByDocumentIdAndControlTagIn(docId, tags);
        }

        return entities.stream().map(this::toDto).toList();
    }

    @Transactional
    public List<PlaceholderConfigDto> syncPlaceholders(String documentId, List<PlaceholderSyncItem> items) {
        String docId = normalizeDocumentId(documentId);
        if (items != null) {
            for (PlaceholderSyncItem item : items) {
                if (item.tag() != null && !item.tag().isBlank() && item.alias() != null && !item.alias().isBlank()) {
                    Optional<ReportPlaceholderConfigEntity> existing =
                            configRepository.findByDocumentIdAndControlTag(docId, item.tag().trim());
                    if (existing.isPresent()) {
                        ReportPlaceholderConfigEntity config = existing.get();
                        if (!Objects.equals(config.getControlAlias(), item.alias().trim())) {
                            config.setControlAlias(item.alias().trim());
                            configRepository.save(config);
                        }
                    }
                }
            }
        }
        return findConfigurations(docId, null);
    }

    @Transactional
    public GenerateResult generate(GenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Generate request is required");
        }
        String tag = request.tag() == null ? "" : request.tag().trim();
        if (tag.isBlank()) {
            throw new IllegalArgumentException("A content control tag is required");
        }
        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("Enter a valid prompt before generating a Blueprint");
        }
        String docId = normalizeDocumentId(request.documentId());
        String alias = request.alias() == null ? null : request.alias().trim();

        String duration = extractDuration(prompt);

        // 1. Generate & Validate Blueprint
        ReportBlueprint blueprint = reportBlueprintService.generate(prompt);

        // 2. Generate standard SQL
        String sql = reportQueryService.generateSql(blueprint);

        // 3. Save or update config
        Optional<ReportPlaceholderConfigEntity> existingOpt =
                configRepository.findByDocumentIdAndControlTag(docId, tag);
        ReportPlaceholderConfigEntity config;
        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            config.incrementRevision();
            config.setControlAlias(alias);
            config.setPrompt(prompt);
            config.setDuration(duration);
            config.setExecutionPlan(blueprint);
            config.setGeneratedSql(sql);
            config.setFormat(blueprint.format());
        } else {
            config = new ReportPlaceholderConfigEntity(
                    docId, tag, alias, prompt, duration, blueprint, sql, blueprint.format());
        }
        config = configRepository.save(config);

        // 4. Create query run
        ReportQueryRunEntity run = new ReportQueryRunEntity(
                config,
                config.getRevision(),
                prompt,
                blueprint,
                sql);
        run = runRepository.save(run);

        // 5. Execute SQL
        ReportQueryService.QueryResult queryResult = null;
        String executionError = null;
        try {
            queryResult = reportQueryService.execute(blueprint);
            run.markSucceeded(queryResult);
        } catch (Exception exception) {
            executionError = exception.getMessage();
            run.markFailed(executionError);
        }
        run = runRepository.save(run);

        return new GenerateResult(
                config.getId(),
                run.getId(),
                config.getRevision(),
                blueprint,
                queryResult,
                executionError);
    }

    @Transactional
    public ReportQueryService.QueryResult executeRun(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID is required");
        }
        ReportQueryRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Query run not found: " + runId));

        if (run.getPlanSnapshot() == null) {
            throw new IllegalArgumentException("Query run " + runId + " has no execution plan snapshot");
        }

        try {
            ReportQueryService.QueryResult result = reportQueryService.execute(run.getPlanSnapshot());
            run.markSucceeded(result);
            runRepository.save(run);
            return result;
        } catch (Exception exception) {
            run.markFailed(exception.getMessage());
            runRepository.save(run);
            throw exception;
        }
    }

    @Transactional
    public ApplyResult applyRun(UUID runId, String requestedAlias) throws Exception {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID is required to apply changes");
        }
        ReportQueryRunEntity run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Query run not found: " + runId));

        if (run.getStatus() != ReportQueryRunStatus.SUCCEEDED && run.getStatus() != ReportQueryRunStatus.APPLIED) {
            throw new IllegalArgumentException(
                    "Only successful query runs can be applied. Current status: " + run.getStatus());
        }

        ReportPlaceholderConfigEntity config = run.getPlaceholderConfig();
        if (!Objects.equals(run.getConfigRevision(), config.getRevision())) {
            throw new IllegalArgumentException(
                    "Prompt has changed since this result was generated. Generate again before applying.");
        }

        String alias = (requestedAlias != null && !requestedAlias.isBlank())
                ? requestedAlias.trim()
                : config.getControlAlias();
        if (alias == null || alias.isBlank()) {
            throw new IllegalArgumentException("A content control Alias is required for applying");
        }

        String formattedValue = formatValue(run.getResultSnapshot(), config.getFormat());

        byte[] currentDoc = documentStore.loadOrCreateDefaultDocument();
        ReportContentControlUpdater.UpdateResult updateResult =
                contentControlUpdater.replaceContentByAlias(currentDoc, alias, formattedValue);
        documentStore.saveDefaultDocument(updateResult.documentBytes());

        run.markApplied();
        runRepository.save(run);

        return new ApplyResult(
                documentStore.currentVersion(),
                updateResult.updatedControls(),
                run.getId(),
                formattedValue);
    }

    public String formatValue(ReportQueryService.QueryResult queryResult, String format) {
        if (queryResult == null || queryResult.rows() == null || queryResult.rows().isEmpty()) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        Map<String, Object> firstRow = queryResult.rows().getFirst();
        if (firstRow == null || firstRow.isEmpty()) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        String firstColumn = (queryResult.columns() != null && !queryResult.columns().isEmpty())
                ? queryResult.columns().getFirst()
                : firstRow.keySet().iterator().next();
        Object valueObj = firstRow.get(firstColumn);
        if (valueObj == null) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        String value = String.valueOf(valueObj);
        if ("percentage".equalsIgnoreCase(format) && !value.trim().endsWith("%")) {
            value = value + "%";
        }
        return value;
    }

    private PlaceholderConfigDto toDto(ReportPlaceholderConfigEntity entity) {
        Optional<ReportQueryRunEntity> latestRun =
                runRepository.findFirstByPlaceholderConfigIdOrderByExecutedAtDesc(entity.getId());
        PlaceholderRunDto runDto = latestRun.map(run -> new PlaceholderRunDto(
                run.getId(),
                run.getConfigRevision(),
                run.getPromptSnapshot(),
                run.getPlanSnapshot(),
                run.getExecutedSql(),
                run.getResultSnapshot(),
                run.getStatus(),
                run.getErrorMessage(),
                run.getExecutedAt(),
                run.getAppliedAt()
        )).orElse(null);

        return new PlaceholderConfigDto(
                entity.getId(),
                entity.getDocumentId(),
                entity.getControlTag(),
                entity.getControlAlias(),
                entity.getPrompt(),
                entity.getDuration(),
                entity.getExecutionPlan(),
                entity.getGeneratedSql(),
                entity.getFormat(),
                entity.getRevision(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                runDto
        );
    }

    private String extractDuration(String prompt) {
        if (prompt == null) {
            return null;
        }
        Matcher matcher = DURATION_PATTERN.matcher(prompt.trim());
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String normalizeDocumentId(String documentId) {
        return (documentId == null || documentId.isBlank()) ? "default" : documentId.trim();
    }

    public record GenerateRequest(
            String documentId,
            String tag,
            String alias,
            String prompt) {
    }

    public record GenerateResult(
            UUID configId,
            UUID runId,
            Long revision,
            ReportBlueprint blueprint,
            ReportQueryService.QueryResult result,
            String error) {
    }

    public record PlaceholderSyncItem(
            String tag,
            String alias) {
    }

    public record ApplyResult(
            long documentVersion,
            int updatedControls,
            UUID runId,
            String appliedValue) {
    }

    public record PlaceholderConfigDto(
            UUID id,
            String documentId,
            String tag,
            String alias,
            String prompt,
            String duration,
            ReportBlueprint executionPlan,
            String generatedSql,
            String format,
            Long revision,
            Instant createdAt,
            Instant updatedAt,
            PlaceholderRunDto lastRun) {
    }

    public record PlaceholderRunDto(
            UUID id,
            Long configRevision,
            String promptSnapshot,
            ReportBlueprint planSnapshot,
            String executedSql,
            ReportQueryService.QueryResult result,
            ReportQueryRunStatus status,
            String errorMessage,
            Instant executedAt,
            Instant appliedAt) {
    }
}
