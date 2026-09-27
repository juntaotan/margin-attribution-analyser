package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "report_placeholder_config",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_report_placeholder_config_doc_tag",
                columnNames = {"document_id", "control_tag"}
        )
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportPlaceholderConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false, length = 100)
    private String documentId = "default";

    @Column(name = "control_tag", nullable = false, length = 255)
    private String controlTag;

    @Column(name = "control_alias", length = 255)
    private String controlAlias;

    @Column(name = "prompt", columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "duration", length = 255)
    private String duration;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_plan", columnDefinition = "jsonb")
    private ReportBlueprint executionPlan;

    @Column(name = "generated_sql", columnDefinition = "TEXT")
    private String generatedSql;

    @Column(name = "format", length = 50)
    private String format;

    @Column(name = "revision", nullable = false)
    private Long revision = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ReportPlaceholderConfigEntity(
            String documentId,
            String controlTag,
            String controlAlias,
            String prompt,
            String duration,
            ReportBlueprint executionPlan,
            String generatedSql,
            String format) {
        this.documentId = documentId == null || documentId.isBlank() ? "default" : documentId.trim();
        this.controlTag = controlTag == null ? "" : controlTag.trim();
        this.controlAlias = controlAlias == null ? null : controlAlias.trim();
        this.prompt = prompt;
        this.duration = duration;
        this.executionPlan = executionPlan;
        this.generatedSql = generatedSql;
        this.format = format;
        this.revision = 1L;
    }

    public void incrementRevision() {
        this.revision = (this.revision == null ? 0L : this.revision) + 1L;
    }
}
