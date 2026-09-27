CREATE TABLE ai_provider_settings
(
    id         BIGINT PRIMARY KEY,
    host       VARCHAR(255) NOT NULL,
    port       INTEGER NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT ck_ai_provider_settings_singleton CHECK (id = 1),
    CONSTRAINT ck_ai_provider_settings_port CHECK (port BETWEEN 1 AND 65535)
);
