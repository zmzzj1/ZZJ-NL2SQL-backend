-- P12 S3：PostgreSQL pgvector 向量表（独立 PG 数据源，与 MySQL 管理库分离）。
-- 在 rageng（或专用库）手动执行；不引入 Flyway。
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_vectors (
    id              BIGSERIAL       PRIMARY KEY,
    vector_type     VARCHAR(32)     NOT NULL,  -- GLOSSARY / QUESTION_KNOWLEDGE / MEMORY
    source_id       BIGINT          NOT NULL,  -- 对应 MySQL 知识条目 / 记忆 id
    owner_id        BIGINT          NOT NULL,
    connection_id   BIGINT,                    -- 可空（Memory 不限连接）
    workspace_id    BIGINT,
    embedding       vector(1024)    NOT NULL,
    content_summary TEXT,
    active          BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS knowledge_vectors_embedding_idx
    ON knowledge_vectors USING hnsw (embedding vector_cosine_ops);
