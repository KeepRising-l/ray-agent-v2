-- 当前由 Spring SQL 初始化显式加载；文件编号保留规划命名，尚未引入 Flyway。
CREATE TABLE IF NOT EXISTS ra_knowledge_base (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    created_at BIGINT NOT NULL,
    UNIQUE(owner_id, name)
);
CREATE TABLE IF NOT EXISTS ra_document (
    id VARCHAR(36) PRIMARY KEY,
    kb_id VARCHAR(36) NOT NULL REFERENCES ra_knowledge_base(id),
    owner_id VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    latest_version INTEGER NOT NULL DEFAULT 0,
    active_version_id VARCHAR(36),
    created_at BIGINT NOT NULL,
    UNIQUE(kb_id, name)
);
CREATE TABLE IF NOT EXISTS ra_document_version (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES ra_document(id),
    version INTEGER NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    file_size INTEGER NOT NULL,
    file_type VARCHAR(10) NOT NULL,
    storage_key VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    parser_version VARCHAR(60) NOT NULL,
    chunk_policy VARCHAR(100) NOT NULL,
    embedding_profile VARCHAR(64),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    failure_stage VARCHAR(30),
    error_message VARCHAR(300),
    created_at BIGINT NOT NULL,
    ended_at BIGINT,
    UNIQUE(document_id, version)
);
CREATE TABLE IF NOT EXISTS ra_document_chunk (
    id VARCHAR(36) PRIMARY KEY,
    version_id VARCHAR(36) NOT NULL REFERENCES ra_document_version(id),
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    page INTEGER,
    paragraph INTEGER NOT NULL,
    heading VARCHAR(300) NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    UNIQUE(version_id, ordinal)
);
CREATE INDEX IF NOT EXISTS idx_kb_owner ON ra_knowledge_base(owner_id);
CREATE INDEX IF NOT EXISTS idx_doc_kb ON ra_document(kb_id);
CREATE INDEX IF NOT EXISTS idx_version_status ON ra_document_version(status, created_at);
