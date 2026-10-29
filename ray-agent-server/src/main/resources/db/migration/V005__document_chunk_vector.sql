-- 扩展固定安装到 public；隔离测试 schema 使用限定类型名，避免依赖 search_path。
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE TABLE IF NOT EXISTS ra_embedding_profile (
    id VARCHAR(64) PRIMARY KEY,
    model VARCHAR(200) NOT NULL,
    dimension INTEGER NOT NULL CHECK(dimension > 0 AND dimension <= 4096),
    revision VARCHAR(100) NOT NULL
);
CREATE TABLE IF NOT EXISTS ra_chunk_vector (
    chunk_id VARCHAR(36) PRIMARY KEY REFERENCES ra_document_chunk(id) ON DELETE CASCADE,
    profile_id VARCHAR(64) NOT NULL REFERENCES ra_embedding_profile(id),
    dimension INTEGER NOT NULL,
    embedding public.vector NOT NULL,
    CHECK(public.vector_dims(embedding) = dimension),
    CHECK(dimension > 0 AND dimension <= 4096)
);
CREATE INDEX IF NOT EXISTS idx_vector_profile ON ra_chunk_vector(profile_id);
