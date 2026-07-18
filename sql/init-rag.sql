-- RAG Service 数据库初始化
-- PostgreSQL 16 + pgvector

CREATE EXTENSION IF NOT EXISTS pgvector;

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS zhparser;
    CREATE TEXT SEARCH CONFIGURATION chinese (PARSER = zhparser);
    ALTER TEXT SEARCH CONFIGURATION chinese ADD MAPPING FOR n,v,a,i,e,l WITH simple;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'zhparser not available, using simple tokenizer';
END $$;

CREATE TABLE IF NOT EXISTS rag_knowledge_base (
    id              BIGSERIAL PRIMARY KEY,
    merchant_id     BIGINT NOT NULL,
    name            VARCHAR(100) NOT NULL,
    description     VARCHAR(500),
    chunk_size      INT DEFAULT 500,
    chunk_overlap   INT DEFAULT 50,
    embedding_model VARCHAR(100) DEFAULT 'embedding-2',
    created_by      BIGINT,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_kb_merchant ON rag_knowledge_base(merchant_id);

CREATE TABLE IF NOT EXISTS rag_document (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES rag_knowledge_base(id) ON DELETE CASCADE,
    filename    VARCHAR(255) NOT NULL,
    file_type   VARCHAR(20) NOT NULL,
    file_size   BIGINT,
    file_path   VARCHAR(500),
    status      VARCHAR(20) DEFAULT 'PROCESSING',
    chunk_count INT DEFAULT 0,
    error_msg   TEXT,
    uploaded_by BIGINT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON rag_document(kb_id);

CREATE TABLE IF NOT EXISTS rag_document_chunk (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_document(id) ON DELETE CASCADE,
    kb_id       BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content     TEXT NOT NULL,
    embedding   vector(1024),
    tsv         tsvector,
    metadata    JSONB DEFAULT '{}',
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON rag_document_chunk USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_chunk_tsv ON rag_document_chunk USING GIN (tsv);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON rag_document_chunk(kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_doc ON rag_document_chunk(document_id);

CREATE TABLE IF NOT EXISTS rag_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT,
    action        VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id   BIGINT,
    detail        JSONB DEFAULT '{}',
    ip            VARCHAR(45),
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_audit_user ON rag_audit_log(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_time ON rag_audit_log(created_at DESC);
