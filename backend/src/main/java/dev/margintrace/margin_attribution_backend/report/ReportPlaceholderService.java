package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages saved placeholder configurations for Report Studio: the prompt, generated blueprint and SQL for each
 * content-control tag, and the history of query runs.
 *
 * <p>Each configuration has a revision that increases when its prompt is regenerated. A run records the revision it
 * was produced from, and only a successful run of the current revision can be written into the document.
 */
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

    /**
     * @param documentId the document, or blank for {@code default}
     * @param tags the tags to return, or empty for all
     * @return the matching configurations, each with its latest run
     */
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

    /**
     * A default prompt and output format for a known placeholder.
     */
    public record PreconfiguredPrompt(String prompt, String format) {}

    /**
     * Default prompts for the standard management-report placeholders, keyed by tag.
     */
    public static final Map<String, PreconfiguredPrompt> DEFAULT_PROMPTS = Map.ofEntries(
            Map.entry(
                    "Revenue",
                    new PreconfiguredPrompt(
                            "Query sales_details to calculate sum(total_revenue) for the active period", "currency")),
            Map.entry(
                    "Revenue Change %",
                    new PreconfiguredPrompt(
                            "Compare sum(total_revenue) from sales_details across current and baseline periods",
                            "percentage")),
            Map.entry(
                    "Gross Margin",
                    new PreconfiguredPrompt(
                            "Query sales_details to calculate sum(total_revenue - total_cost) for the active period",
                            "currency")),
            Map.entry(
                    "Gross Margin %",
                    new PreconfiguredPrompt(
                            "Query sales_details to calculate round(sum(total_revenue - total_cost) / nullif(sum(total_revenue), 0) * 100, 2)",
                            "percentage")),
            Map.entry(
                    "Cost of Goods Sold",
                    new PreconfiguredPrompt(
                            "Query sales_details to calculate sum(total_cost) for the active period", "currency")),
            Map.entry(
                    "Current Period",
                    new PreconfiguredPrompt(
                            "Extract the active reporting cycle period from sales_details date range", "text")),
            Map.entry(
                    "Comparison Period",
                    new PreconfiguredPrompt(
                            "Extract the comparable baseline cycle period from historical sales_details date range",
                            "text")),
            Map.entry(
                    "PPV Variance",
                    new PreconfiguredPrompt(
                            "Query purchases for purchase price variance in the active period", "percentage")),
            Map.entry(
                    "Usage Variance",
                    new PreconfiguredPrompt(
                            "Query inventory_usage to calculate sum(material_total_cost) for the active period",
                            "currency")),
            Map.entry(
                    "Scrap Loss",
                    new PreconfiguredPrompt("Aggregate recorded material losses in inventory_usage", "currency")),
            Map.entry(
                    "ECN Impact",
                    new PreconfiguredPrompt("Compare bill_of_material usage across revisions", "currency")));

    /**
     * @param tag a placeholder tag, matched exactly or ignoring case and non-alphanumeric characters
     * @return the default prompt, or null if the tag is unknown or blank
     */
    public PreconfiguredPrompt getPreconfiguredPrompt(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        if (DEFAULT_PROMPTS.containsKey(tag)) {
            return DEFAULT_PROMPTS.get(tag);
        }
        String normalized = tag.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (Map.Entry<String, PreconfiguredPrompt> entry : DEFAULT_PROMPTS.entrySet()) {
            String candidateNorm =
                    entry.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
            if (candidateNorm.equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Registers the tagged controls found in a document. Known configurations get the control's current alias; new
     * tags with a default prompt are saved with that prompt and the default period 2026-01-01 to 2026-01-31. Other
     * new tags are not saved.
     *
     * @param documentId the document, or blank for {@code default}
     * @param items the controls' tags and aliases; items without a tag are ignored
     * @return all configurations of the document
     */
    @Transactional
    public List<PlaceholderConfigDto> syncPlaceholders(String documentId, List<PlaceholderSyncItem> items) {
        String docId = normalizeDocumentId(documentId);
        if (items != null) {
            for (PlaceholderSyncItem item : items) {
                if (item.tag() != null && !item.tag().isBlank()) {
                    String tag = item.tag().trim();
                    String alias = (item.alias() != null && !item.alias().isBlank())
                            ? item.alias().trim()
                            : tag;
                    Optional<ReportPlaceholderConfigEntity> existing =
                            configRepository.findByDocumentIdAndControlTag(docId, tag);
                    if (existing.isPresent()) {
                        ReportPlaceholderConfigEntity config = existing.get();
                        if (!Objects.equals(config.getControlAlias(), alias)) {
                            config.setControlAlias(alias);
                            configRepository.save(config);
                        }
                    } else {
                        // Seed preconfigured prompt on discovery if not yet configured
                        PreconfiguredPrompt preconfigured = getPreconfiguredPrompt(tag);
                        if (preconfigured != null) {
                            String fullPrompt = "Duration: 2026-01-01 to 2026-01-31\n" + preconfigured.prompt();
                            ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                                    docId,
                                    tag,
                                    alias,
                                    fullPrompt,
                                    "2026-01-01 to 2026-01-31",
                                    null,
                                    null,
                                    preconfigured.format());
                            configRepository.save(config);
                        }
                    }
                }
            }
        }
        return findConfigurations(docId, null);
    }

    /**
     * Creates or updates a configuration for every default prompt with the given period. Existing prompts are
     * replaced only when they are blank or were generated from a default ({@code Duration:} prefix).
     *
     * @param documentId the document, or blank for {@code default}
     * @param duration the reporting period, or blank for 2026-01-01 to 2026-01-31
     */
    @Transactional
    public void seedOrUpdateConfigurations(String documentId, String duration) {
        String docId = normalizeDocumentId(documentId);
        String activeDuration = (duration == null || duration.isBlank()) ? "2026-01-01 to 2026-01-31" : duration.trim();
        for (Map.Entry<String, PreconfiguredPrompt> entry : DEFAULT_PROMPTS.entrySet()) {
            String tag = entry.getKey();
            PreconfiguredPrompt defaultPrompt = entry.getValue();
            Optional<ReportPlaceholderConfigEntity> existing =
                    configRepository.findByDocumentIdAndControlTag(docId, tag);
            String fullPrompt = "Duration: " + activeDuration + "\n" + defaultPrompt.prompt();

            if (existing.isPresent()) {
                ReportPlaceholderConfigEntity config = existing.get();
                config.setDuration(activeDuration);
                if (config.getPrompt() == null
                        || config.getPrompt().isBlank()
                        || config.getPrompt().startsWith("Duration:")) {
                    config.setPrompt(fullPrompt);
                }
                configRepository.save(config);
            } else {
                ReportPlaceholderConfigEntity config = new ReportPlaceholderConfigEntity(
                        docId, tag, tag, fullPrompt, activeDuration, null, null, defaultPrompt.format());
                configRepository.save(config);
            }
        }
    }

    /**
     * Generates a blueprint and SQL for a prompt, saves them as a new revision of the tag's configuration, and runs
     * the query as a new query run. A query failure is recorded on the run and returned in {@code error}; it does
     * not undo the saved configuration.
     *
     * @param request the document, tag, alias and prompt; the period is read from a leading {@code Duration:} line
     * @return the configuration and run IDs, the revision, the blueprint, and the result or error
     * @throws IllegalArgumentException if the tag or prompt is blank, or the blueprint is unsafe
     * @throws dev.margintrace.margin_attribution_backend.analysis.ai.AiUnavailableException if the AI service fails or
     *     returns an invalid blueprint
     */
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
        ReportQueryRunEntity run = new ReportQueryRunEntity(config, config.getRevision(), prompt, blueprint, sql);
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
                config.getId(), run.getId(), config.getRevision(), blueprint, queryResult, executionError);
    }

    /**
     * Runs a saved query run's blueprint again and records the outcome on the run.
     *
     * @param runId the run to execute
     * @return the new result
     * @throws IllegalArgumentException if the run does not exist, has no blueprint, or the query fails
     */
    @Transactional
    public ReportQueryService.QueryResult executeRun(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID is required");
        }
        ReportQueryRunEntity run = runRepository
                .findById(runId)
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

    /**
     * Writes a run's formatted result into the matching content controls of the report document and marks the run
     * as applied.
     *
     * @param runId a successful or already applied run of the configuration's current revision
     * @param requestedAlias the alias to write to, or blank for the configuration's alias
     * @return the new document version, the number of controls updated, the run ID and the value written
     * @throws IllegalArgumentException if the run is missing, unsuccessful or outdated, has no result, or no
     *     control matches the alias
     * @throws Exception if the document cannot be loaded or stored
     */
    @Transactional
    public ApplyResult applyRun(UUID runId, String requestedAlias) throws Exception {
        if (runId == null) {
            throw new IllegalArgumentException("Run ID is required to apply changes");
        }
        ReportQueryRunEntity run = runRepository
                .findById(runId)
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
                documentStore.currentVersion(), updateResult.updatedControls(), run.getId(), formattedValue);
    }

    /**
     * Formats the first column of the first result row for the document. Currency values are rounded half-up to two
     * decimal places and shown as {@code $1,234.50}; percentages are rounded half-up to one decimal place and given
     * a {@code %} sign. Both use {@link java.math.BigDecimal}. Values that are not numbers only get the symbol
     * added.
     *
     * @param queryResult the query result
     * @param format {@code currency}, {@code percentage} or any other format, which leaves the value unchanged
     * @return the formatted value
     * @throws IllegalArgumentException if there is no first value
     */
    public String formatValue(ReportQueryService.QueryResult queryResult, String format) {
        if (queryResult == null
                || queryResult.rows() == null
                || queryResult.rows().isEmpty()) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        Map<String, Object> firstRow = queryResult.rows().getFirst();
        if (firstRow == null || firstRow.isEmpty()) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        String firstColumn =
                (queryResult.columns() != null && !queryResult.columns().isEmpty())
                        ? queryResult.columns().getFirst()
                        : firstRow.keySet().iterator().next();
        Object valueObj = firstRow.get(firstColumn);
        if (valueObj == null) {
            throw new IllegalArgumentException("There is no result value to save");
        }
        String value = String.valueOf(valueObj);
        if ("currency".equalsIgnoreCase(format)) {
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP);
                return "$" + String.format(java.util.Locale.US, "%,.2f", bd);
            } catch (Exception ignored) {
                if (!value.trim().startsWith("$")) {
                    return "$" + value;
                }
            }
        } else if ("percentage".equalsIgnoreCase(format)) {
            try {
                java.math.BigDecimal bd = new java.math.BigDecimal(
                                value.replace("%", "").trim())
                        .setScale(1, java.math.RoundingMode.HALF_UP);
                return bd.toPlainString() + "%";
            } catch (Exception ignored) {
                if (!value.trim().endsWith("%")) {
                    return value + "%";
                }
            }
        }
        return value;
    }

    private PlaceholderConfigDto toDto(ReportPlaceholderConfigEntity entity) {
        Optional<ReportQueryRunEntity> latestRun =
                runRepository.findFirstByPlaceholderConfigIdOrderByExecutedAtDesc(entity.getId());
        PlaceholderRunDto runDto = latestRun
                .map(run -> new PlaceholderRunDto(
                        run.getId(),
                        run.getConfigRevision(),
                        run.getPromptSnapshot(),
                        run.getPlanSnapshot(),
                        run.getExecutedSql(),
                        run.getResultSnapshot(),
                        run.getStatus(),
                        run.getErrorMessage(),
                        run.getExecutedAt(),
                        run.getAppliedAt()))
                .orElse(null);

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
                runDto);
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

    /**
     * A prompt to generate for a placeholder tag.
     */
    public record GenerateRequest(String documentId, String tag, String alias, String prompt) {}

    /**
     * Outcome of generation. {@code result} is null and {@code error} set when the query failed.
     */
    public record GenerateResult(
            UUID configId,
            UUID runId,
            Long revision,
            ReportBlueprint blueprint,
            ReportQueryService.QueryResult result,
            String error) {}

    /**
     * A tagged control found in the document.
     */
    public record PlaceholderSyncItem(String tag, String alias) {}

    /**
     * Outcome of writing a run result into the document.
     */
    public record ApplyResult(long documentVersion, int updatedControls, UUID runId, String appliedValue) {}

    /**
     * A saved placeholder configuration with its latest run.
     */
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
            PlaceholderRunDto lastRun) {}

    /**
     * A query run with the snapshots it was produced from.
     */
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
            Instant appliedAt) {}
}
