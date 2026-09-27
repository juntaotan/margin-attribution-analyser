CREATE TABLE report_placeholder_config
(
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id    VARCHAR(100) NOT NULL DEFAULT 'default',
    control_tag    VARCHAR(255) NOT NULL,
    control_alias  VARCHAR(255),
    prompt         TEXT,
    duration       VARCHAR(255),
    execution_plan JSONB,
    generated_sql  TEXT,
    format         VARCHAR(50),
    revision       BIGINT       NOT NULL DEFAULT 1,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_report_placeholder_config_doc_tag UNIQUE (document_id, control_tag)
);

CREATE INDEX idx_report_placeholder_config_doc_tag ON report_placeholder_config (document_id, control_tag);

CREATE TABLE report_query_run
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    placeholder_config_id UUID   NOT NULL,
    config_revision       BIGINT NOT NULL,
    prompt_snapshot       TEXT,
    plan_snapshot         JSONB,
    executed_sql          TEXT,
    result_snapshot       JSONB,
    status                VARCHAR(50) NOT NULL,
    error_message         TEXT,
    executed_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at            TIMESTAMP WITH TIME ZONE,

    CONSTRAINT fk_report_query_run_config
        FOREIGN KEY (placeholder_config_id)
        REFERENCES report_placeholder_config (id)
        ON DELETE CASCADE,
    CONSTRAINT ck_report_query_run_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'APPLIED'))
);

CREATE INDEX idx_report_query_run_config ON report_query_run (placeholder_config_id);
CREATE INDEX idx_report_query_run_status ON report_query_run (status);
