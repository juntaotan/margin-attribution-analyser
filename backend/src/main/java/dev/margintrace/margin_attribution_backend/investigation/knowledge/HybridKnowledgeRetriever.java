package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationConfiguration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hybrid retrieval over the knowledge base, run separately for each {@link SourceType}.
 *
 * <p>Dense retrieval ranks chunks by vector similarity to the query, finding documents that describe the same
 * problem in different words. Exact retrieval ranks chunks that mention the anomaly's material, product or order
 * numbers, which embeddings represent poorly. The two rankings are merged with Reciprocal Rank Fusion
 * (score = Σ 1 / (k + rank), k = {@value #RRF_K}), which needs no score calibration.</p>
 *
 * <p>Engineering changes and quality records are limited to the date window; historical cases are not, because a
 * past case is relevant whenever it happened. If the embedding runtime is unavailable, retrieval continues with
 * exact matching only and says so in the returned messages.</p>
 */
@Component
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class HybridKnowledgeRetriever {
    static final int RRF_K = 60;
    static final int CANDIDATES = 20;

    private final EmbeddingStore<TextSegment> store;
    private final LocalEmbeddingModel embeddingModel;
    private final KnowledgeRepository repository;

    public HybridKnowledgeRetriever(
            EmbeddingStore<TextSegment> store, LocalEmbeddingModel embeddingModel, KnowledgeRepository repository) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.repository = repository;
    }

    /** How a chunk was found. */
    public enum Method {
        DENSE,
        EXACT,
        BOTH
    }

    /** One retrieved chunk with its document fields. */
    public record RetrievedChunk(KnowledgeRepository.ChunkView chunk, Method retrievedBy) {}

    /** Which retrievers run; {@link #HYBRID} in normal use, the others for evaluation. */
    public enum Mode {
        HYBRID,
        DENSE_ONLY,
        EXACT_ONLY
    }

    /** Result per source type plus notices about degraded retrieval. */
    public record Retrieval(Map<SourceType, List<RetrievedChunk>> byType, List<String> messages) {}

    /**
     * @param query   natural-language description of the anomaly
     * @param anchors material, product and order numbers to match exactly
     * @param from    start of the date window for engineering changes and quality records
     * @param to      end of the date window
     * @param limit   chunks kept per source type
     */
    public Retrieval retrieve(
            String query, Collection<String> anchors, LocalDate from, LocalDate to, int limit, Mode mode) {
        List<String> messages = new ArrayList<>();
        Embedding queryEmbedding = null;
        if (mode != Mode.EXACT_ONLY) {
            try {
                queryEmbedding = embeddingModel.embed(query).content();
                if (repository.embeddingSignatures().stream().anyMatch(s -> !s.equals(embeddingModel.signature()))) {
                    messages.add(
                            "Some documents were embedded with a different model; re-upload them for reliable similarity search.");
                }
            } catch (IllegalStateException unavailable) {
                messages.add(
                        "Embedding runtime unavailable (" + unavailable.getMessage() + "); exact code matching only.");
            }
        }
        Map<SourceType, List<RetrievedChunk>> byType = new LinkedHashMap<>();
        for (SourceType type : SourceType.values()) {
            LocalDate start = type == SourceType.CASE ? null : from;
            LocalDate end = type == SourceType.CASE ? null : to;
            List<String> dense = queryEmbedding == null ? List.of() : dense(queryEmbedding, type, start, end);
            List<String> exact = mode == Mode.DENSE_ONLY
                    ? List.of()
                    : repository.findChunkIdsByCodes(anchors, type, start, end, CANDIDATES);
            Map<String, Method> fused = fuse(dense, exact, limit);
            Map<String, KnowledgeRepository.ChunkView> chunks = repository.findChunks(fused.keySet()).stream()
                    .collect(Collectors.toMap(chunk -> chunk.id().toString(), Function.identity()));
            byType.put(
                    type,
                    fused.entrySet().stream()
                            .filter(entry -> chunks.containsKey(entry.getKey()))
                            .map(entry -> new RetrievedChunk(chunks.get(entry.getKey()), entry.getValue()))
                            .toList());
        }
        return new Retrieval(byType, messages);
    }

    private List<String> dense(Embedding queryEmbedding, SourceType type, LocalDate from, LocalDate to) {
        Filter filter = metadataKey("sourceType").isEqualTo(type.name());
        if (from != null) filter = filter.and(metadataKey("effectiveDay").isGreaterThanOrEqualTo(from.toEpochDay()));
        if (to != null) filter = filter.and(metadataKey("effectiveDay").isLessThanOrEqualTo(to.toEpochDay()));
        return store
                .search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(CANDIDATES)
                        .filter(filter)
                        .build())
                .matches()
                .stream()
                .map(EmbeddingMatch::embeddingId)
                .toList();
    }

    /**
     * Reciprocal Rank Fusion of two ranked ID lists.
     *
     * @return up to {@code limit} IDs in fused order, each with the retrievers that found it; ties keep dense order
     */
    static Map<String, Method> fuse(List<String> dense, List<String> exact, int limit) {
        Map<String, Double> scores = new HashMap<>();
        Map<String, Method> methods = new HashMap<>();
        Map<String, Integer> firstSeen = new HashMap<>();
        accumulate(dense, Method.DENSE, scores, methods, firstSeen);
        accumulate(exact, Method.EXACT, scores, methods, firstSeen);
        Map<String, Method> fused = new LinkedHashMap<>();
        scores.keySet().stream()
                .sorted(Comparator.comparing((String id) -> -scores.get(id)).thenComparing(firstSeen::get))
                .limit(limit)
                .forEach(id -> fused.put(id, methods.get(id)));
        return fused;
    }

    private static void accumulate(
            List<String> ranking,
            Method method,
            Map<String, Double> scores,
            Map<String, Method> methods,
            Map<String, Integer> firstSeen) {
        for (int rank = 0; rank < ranking.size(); rank++) {
            String id = ranking.get(rank);
            scores.merge(id, 1.0 / (RRF_K + rank + 1), Double::sum);
            methods.merge(id, method, (existing, added) -> existing == added ? existing : Method.BOTH);
            firstSeen.putIfAbsent(id, firstSeen.size());
        }
    }
}
