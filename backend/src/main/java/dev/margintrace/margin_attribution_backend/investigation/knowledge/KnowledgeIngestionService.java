package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.margintrace.margin_attribution_backend.datalake.storage.RawFileStorage;
import dev.margintrace.margin_attribution_backend.investigation.InvestigationConfiguration;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds documents to and removes them from the investigation knowledge base.
 *
 * <p>Ingestion keeps the raw file in object storage, extracts text ({@code .docx}, {@code .txt}, {@code .md}, or
 * {@code .csv} with one passage per row), splits it into chunks of at most {@value #CHUNK_TOKENS} model tokens,
 * records which known material, product and order numbers each chunk mentions, and stores one vector per chunk.
 * Embedding happens before any database write and vectors are stored last, so a failure leaves no partial rows;
 * the raw file is stored first and is not removed if a later step fails.</p>
 */
@Service
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class KnowledgeIngestionService {
    static final int CHUNK_TOKENS = 200;
    static final int OVERLAP_TOKENS = 30;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_./-]{0,98}[A-Za-z0-9]");

    private final KnowledgeRepository repository;
    private final EmbeddingStore<TextSegment> store;
    private final LocalEmbeddingModel embeddingModel;
    private final RawFileStorage rawFileStorage;
    private final MinioClient minioClient;
    private final String rawBucket;

    public KnowledgeIngestionService(KnowledgeRepository repository, EmbeddingStore<TextSegment> store,
                                     LocalEmbeddingModel embeddingModel, RawFileStorage rawFileStorage,
                                     MinioClient minioClient, @Value("${datalake.raw-bucket}") String rawBucket) {
        this.repository = repository;
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.rawFileStorage = rawFileStorage;
        this.minioClient = minioClient;
        this.rawBucket = rawBucket;
    }

    /**
     * Ingests one uploaded document.
     *
     * @return the stored document
     * @throws IllegalArgumentException for a missing field, unsupported format or a document without text
     * @throws IllegalStateException    if the embedding runtime is unavailable
     */
    @Transactional
    public KnowledgeRepository.KnowledgeDocument ingest(MultipartFile file, SourceType sourceType, String title,
                                                        LocalDate effectiveDate) throws Exception {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A non-empty file is required");
        if (sourceType == null) throw new IllegalArgumentException("sourceType must be ECN, QUALITY or CASE");
        if (title == null || title.isBlank() || title.length() > 300) throw new IllegalArgumentException("A title of up to 300 characters is required");
        if (effectiveDate == null) throw new IllegalArgumentException("effectiveDate is required");
        String fileName = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        List<String> passages;
        try (InputStream input = file.getInputStream()) {
            passages = passages(fileName, input);
        }
        UUID id = UUID.randomUUID();
        String objectKey = "knowledge/" + id + "/" + fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        rawFileStorage.store(file, objectKey);
        return add(id, sourceType, title.trim(), effectiveDate, fileName, objectKey, passages);
    }

    /**
     * Splits, embeds and stores passages of an already-extracted document. Also used by the evaluation fixture.
     */
    @Transactional
    public KnowledgeRepository.KnowledgeDocument add(UUID id, SourceType sourceType, String title, LocalDate effectiveDate,
                                                     String fileName, String objectKey, List<String> passages) {
        DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_TOKENS, OVERLAP_TOKENS, embeddingModel);
        Metadata metadata = new Metadata()
                .put("documentId", id.toString())
                .put("sourceType", sourceType.name())
                .put("effectiveDay", effectiveDate.toEpochDay());
        List<TextSegment> segments = new ArrayList<>();
        for (String passage : passages) {
            if (!passage.isBlank()) segments.addAll(splitter.split(Document.from(passage.strip(), metadata.copy())));
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("The document contains no text");
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        var document = new KnowledgeRepository.KnowledgeDocument(id, sourceType, title, effectiveDate, fileName,
                objectKey, embeddingModel.signature(), OffsetDateTime.now(ZoneOffset.UTC));
        repository.insertDocument(document);
        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            UUID chunkId = UUID.randomUUID();
            chunkIds.add(chunkId.toString());
            String text = segments.get(i).text();
            repository.insertChunk(chunkId, id, i, text, repository.knownCodes(candidateCodes(text)));
        }
        store.addAll(chunkIds, embeddings, segments);
        return document;
    }

    /** Removes a document's vectors, rows and raw file. */
    @Transactional
    public void delete(UUID id) throws Exception {
        var document = repository.findDocument(id)
                .orElseThrow(() -> new IllegalArgumentException("Knowledge document not found: " + id));
        List<String> chunkIds = repository.findChunkIds(id);
        if (!chunkIds.isEmpty()) store.removeAll(chunkIds);
        repository.deleteDocument(id);
        minioClient.removeObject(RemoveObjectArgs.builder().bucket(rawBucket).object(document.objectKey()).build());
    }

    static List<String> passages(String fileName, InputStream input) throws IOException {
        String extension = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        return switch (extension) {
            case "docx" -> {
                try (XWPFDocument docx = new XWPFDocument(input); XWPFWordExtractor extractor = new XWPFWordExtractor(docx)) {
                    yield List.of(extractor.getText());
                }
            }
            case "txt", "md" -> List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            case "csv" -> csvRows(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            default -> throw new IllegalArgumentException("Supported formats are .docx, .txt, .md and .csv");
        };
    }

    /** One passage per data row, written as "header: value; ..." so each row is self-describing. */
    static List<String> csvRows(String content) {
        List<List<String>> rows = parseCsv(content.startsWith("﻿") ? content.substring(1) : content);
        if (rows.isEmpty()) return List.of();
        List<String> header = rows.get(0);
        List<String> passages = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            StringBuilder passage = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (row.get(i).isBlank()) continue;
                if (!passage.isEmpty()) passage.append("; ");
                passage.append(i < header.size() ? header.get(i) : "column " + (i + 1)).append(": ").append(row.get(i).strip());
            }
            passages.add(passage.toString());
        }
        return passages;
    }

    private static List<List<String>> parseCsv(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < content.length() && content.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(field.toString()); field.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else field.append(c);
        }
        row.add(field.toString());
        if (row.stream().anyMatch(value -> !value.isBlank())) rows.add(row);
        return rows;
    }

    /** Code-like tokens (containing a digit or hyphen) to check against warehouse numbers. */
    static Set<String> candidateCodes(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.chars().anyMatch(Character::isDigit) || token.contains("-")) tokens.add(token);
        }
        return tokens;
    }
}
