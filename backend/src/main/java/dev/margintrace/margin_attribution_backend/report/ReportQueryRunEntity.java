package dev.margintrace.margin_attribution_backend.report;

import dev.margintrace.margin_attribution_backend.report.ai.ReportBlueprint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_query_run")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReportQueryRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "placeholder_config_id", nullable = false)
    private ReportPlaceholderConfigEntity placeholderConfig;

    @Column(name = "config_revision", nullable = false)
    private Long configRevision;

    @Column(name = "prompt_snapshot", columnDefinition = "TEXT")
    private String promptSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_snapshot", columnDefinition = "jsonb")
    private ReportBlueprint planSnapshot;

    @Column(name = "executed_sql", columnDefinition = "TEXT")
    private String executedSql;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_snapshot", columnDefinition = "jsonb")
    private ReportQueryService.QueryResult resultSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ReportQueryRunStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public ReportQueryRunEntity(
            ReportPlaceholderConfigEntity placeholderConfig,
            Long configRevision,
            String promptSnapshot,
            ReportBlueprint planSnapshot,
            String executedSql) {
        this.placeholderConfig = placeholderConfig;
        this.configRevision = configRevision;
        this.promptSnapshot = promptSnapshot;
        this.planSnapshot = planSnapshot;
        this.executedSql = executedSql;
        this.status = ReportQueryRunStatus.RUNNING;
        this.executedAt = Instant.now();
    }

    public void markSucceeded(ReportQueryService.QueryResult result) {
        this.status = ReportQueryRunStatus.SUCCEEDED;
        this.resultSnapshot = result;
        this.errorMessage = null;
    }

    public void markFailed(String message) {
        this.status = ReportQueryRunStatus.FAILED;
        this.errorMessage = message;
    }

    public void markApplied() {
        this.status = ReportQueryRunStatus.APPLIED;
        this.appliedAt = Instant.now();
    }
}
