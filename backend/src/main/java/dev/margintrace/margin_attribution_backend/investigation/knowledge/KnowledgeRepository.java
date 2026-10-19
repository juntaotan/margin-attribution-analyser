package dev.margintrace.margin_attribution_backend.investigation.knowledge;

import dev.margintrace.margin_attribution_backend.investigation.InvestigationConfiguration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Relational part of the knowledge base: documents, their chunks and the business codes each chunk mentions.
 * Uses plain JDBC rather than JPA entities so the main schema validation is unaffected when the feature is off.
 * Vectors live separately in the {@code knowledge_embedding} store under the same chunk IDs.
 */
@Repository
@ConditionalOnProperty(name = InvestigationConfiguration.ENABLED, havingValue = "true")
public class KnowledgeRepository {
    private static final int IN_BATCH = 500;
    private final JdbcClient jdbc;

    public KnowledgeRepository(JdbcClient jdbc, InvestigationConfiguration.InvestigationSchema schema) {
        this.jdbc = jdbc;
    }

    /** Stored knowledge document; {@code embeddingSignature} identifies the model that embedded its chunks. */
    public record KnowledgeDocument(
            UUID id,
            SourceType sourceType,
            String title,
            LocalDate effectiveDate,
            String fileName,
            String objectKey,
            String embeddingSignature,
            OffsetDateTime createdAt) {}

    /** A chunk joined with its document's descriptive fields. */
    public record ChunkView(
            UUID id, UUID documentId, SourceType sourceType, String title, LocalDate effectiveDate, String text) {}

    /**
     * @param document the document metadata to store
     */
    public void insertDocument(KnowledgeDocument document) {
        jdbc.sql(
                        """
                INSERT INTO knowledge_document (id, source_type, title, effective_date, file_name, object_key,
                                                embedding_signature, created_at)
                VALUES (:id, :type, :title, :date, :file, :key, :signature, :created)""")
                .param("id", document.id())
                .param("type", document.sourceType().name())
                .param("title", document.title())
                .param("date", document.effectiveDate())
                .param("file", document.fileName())
                .param("key", document.objectKey())
                .param("signature", document.embeddingSignature())
                .param("created", document.createdAt())
                .update();
    }

    /**
     * Stores a chunk of a document and the business codes it mentions.
     *
     * @param id the chunk ID, also used for its vector
     * @param documentId the owning document
     * @param ordinal the chunk's position in the document
     * @param text the chunk text
     * @param codes the material, product or production-order numbers found in the text
     */
    public void insertChunk(UUID id, UUID documentId, int ordinal, String text, Set<String> codes) {
        jdbc.sql("INSERT INTO knowledge_chunk (id, document_id, ordinal, text) VALUES (:id, :doc, :ordinal, :text)")
                .param("id", id)
                .param("doc", documentId)
                .param("ordinal", ordinal)
                .param("text", text)
                .update();
        for (String code : codes) {
            jdbc.sql("INSERT INTO knowledge_chunk_code (chunk_id, code) VALUES (:id, :code)")
                    .param("id", id)
                    .param("code", code)
                    .update();
        }
    }

    /**
     * @return all documents, newest effective date first, then by title
     */
    public List<KnowledgeDocument> findDocuments() {
        return jdbc.sql("SELECT * FROM knowledge_document ORDER BY effective_date DESC, title")
                .query(this::document)
                .list();
    }

    /**
     * @return the document, or empty if it does not exist
     */
    public Optional<KnowledgeDocument> findDocument(UUID id) {
        return jdbc.sql("SELECT * FROM knowledge_document WHERE id = :id")
                .param("id", id)
                .query(this::document)
                .optional();
    }

    /**
     * @return the IDs of the document's chunks, which are also their vector IDs
     */
    public List<String> findChunkIds(UUID documentId) {
        return jdbc.sql("SELECT id FROM knowledge_chunk WHERE document_id = :doc")
                .param("doc", documentId)
                .query((rs, row) -> rs.getObject("id", UUID.class).toString())
                .list();
    }

    /** Deletes the document; chunks and codes cascade. */
    public void deleteDocument(UUID id) {
        jdbc.sql("DELETE FROM knowledge_document WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * @return the distinct embedding signatures of the stored documents
     */
    public Set<String> embeddingSignatures() {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT embedding_signature FROM knowledge_document")
                .query(String.class)
                .list());
    }

    /**
     * Returns the candidate tokens that are known material, product or production-order numbers in the warehouse.
     */
    public Set<String> knownCodes(Collection<String> tokens) {
        Set<String> known = new HashSet<>();
        List<String> all = new ArrayList<>(tokens);
        for (int start = 0; start < all.size(); start += IN_BATCH) {
            List<String> batch = all.subList(start, Math.min(all.size(), start + IN_BATCH));
            known.addAll(jdbc.sql(
                            """
                    SELECT material_no FROM material_master WHERE material_no IN (:t)
                    UNION SELECT product_no FROM bill_of_material WHERE product_no IN (:t)
                    UNION SELECT material_no FROM bill_of_material WHERE material_no IN (:t)
                    UNION SELECT product_no FROM inventory_usage WHERE product_no IN (:t)
                    UNION SELECT material_no FROM inventory_usage WHERE material_no IN (:t)
                    UNION SELECT order_no FROM inventory_usage WHERE order_no IN (:t)""")
                    .param("t", batch)
                    .query(String.class)
                    .list());
        }
        return known;
    }

    /**
     * Material-master descriptions ("name, specification") for the given codes; codes without a name or
     * specification are omitted.
     */
    public Map<String, String> describe(Collection<String> codes) {
        Map<String, String> descriptions = new HashMap<>();
        if (codes.isEmpty()) return descriptions;
        jdbc.sql("SELECT material_no, material_name, specification FROM material_master WHERE material_no IN (:codes)")
                .param("codes", codes)
                .query((rs, row) -> {
                    String description = String.join(
                            ", ",
                            java.util.stream.Stream.of(rs.getString("material_name"), rs.getString("specification"))
                                    .filter(value -> value != null && !value.isBlank())
                                    .toList());
                    if (!description.isEmpty()) descriptions.put(rs.getString("material_no"), description);
                    return null;
                })
                .list();
        return descriptions;
    }

    /**
     * Exact retrieval: chunks of one source type mentioning any of the codes, ranked by the number of distinct
     * matching codes and then by the most recent document.
     *
     * @param from inclusive lower date bound, or null for no bound
     * @param to   inclusive upper date bound, or null for no bound
     * @return chunk IDs in rank order
     */
    public List<String> findChunkIdsByCodes(
            Collection<String> codes, SourceType type, LocalDate from, LocalDate to, int limit) {
        if (codes.isEmpty()) return List.of();
        String dates = (from == null ? "" : " AND d.effective_date >= :from")
                + (to == null ? "" : " AND d.effective_date <= :to");
        var statement = jdbc.sql(
                        """
                SELECT c.id, COUNT(DISTINCT k.code) AS matches, d.effective_date
                FROM knowledge_chunk_code k
                JOIN knowledge_chunk c ON c.id = k.chunk_id
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE k.code IN (:codes) AND d.source_type = :type"""
                                + dates
                                + """

                GROUP BY c.id, d.effective_date
                ORDER BY matches DESC, d.effective_date DESC, c.id
                LIMIT :limit""")
                .param("codes", codes)
                .param("type", type.name())
                .param("limit", limit);
        if (from != null) statement = statement.param("from", from);
        if (to != null) statement = statement.param("to", to);
        return statement
                .query((rs, row) -> rs.getObject("id", UUID.class).toString())
                .list();
    }

    public List<ChunkView> findChunks(Collection<String> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql(
                        """
                SELECT c.id, c.document_id, c.text, d.source_type, d.title, d.effective_date
                FROM knowledge_chunk c JOIN knowledge_document d ON d.id = c.document_id
                WHERE c.id IN (:ids)""")
                .param("ids", ids.stream().map(UUID::fromString).toList())
                .query((rs, row) -> new ChunkView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("document_id", UUID.class),
                        SourceType.valueOf(rs.getString("source_type")),
                        rs.getString("title"),
                        rs.getObject("effective_date", LocalDate.class),
                        rs.getString("text")))
                .list();
    }

    private KnowledgeDocument document(ResultSet rs, int row) throws SQLException {
        return new KnowledgeDocument(
                rs.getObject("id", UUID.class),
                SourceType.valueOf(rs.getString("source_type")),
                rs.getString("title"),
                rs.getObject("effective_date", LocalDate.class),
                rs.getString("file_name"),
                rs.getString("object_key"),
                rs.getString("embedding_signature"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
