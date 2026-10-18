-- H2 test copy of db/investigation/V1 without pgvector; tests use an in-memory embedding store.
CREATE TABLE knowledge_document
(
    id                  UUID         NOT NULL,
    source_type         VARCHAR(16)  NOT NULL,
    title               VARCHAR(300) NOT NULL,
    effective_date      DATE         NOT NULL,
    file_name           VARCHAR(300) NOT NULL,
    object_key          VARCHAR(500) NOT NULL,
    embedding_signature VARCHAR(500) NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_knowledge_document PRIMARY KEY (id),
    CONSTRAINT ck_knowledge_source_type CHECK (source_type IN ('ECN', 'QUALITY', 'CASE'))
);

CREATE TABLE knowledge_chunk
(
    id          UUID    NOT NULL,
    document_id UUID    NOT NULL,
    ordinal     INTEGER NOT NULL,
    text        TEXT    NOT NULL,
    CONSTRAINT pk_knowledge_chunk PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document (id) ON DELETE CASCADE
);

CREATE TABLE knowledge_chunk_code
(
    chunk_id UUID         NOT NULL,
    code     VARCHAR(100) NOT NULL,
    CONSTRAINT pk_knowledge_chunk_code PRIMARY KEY (chunk_id, code),
    CONSTRAINT fk_knowledge_code_chunk FOREIGN KEY (chunk_id) REFERENCES knowledge_chunk (id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_chunk_code_code ON knowledge_chunk_code (code);
