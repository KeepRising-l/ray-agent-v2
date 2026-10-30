CREATE TABLE IF NOT EXISTS ra_session_knowledge_base (
    session_id VARCHAR(36) NOT NULL REFERENCES ra_session(id),
    kb_id VARCHAR(36) NOT NULL REFERENCES ra_knowledge_base(id),
    PRIMARY KEY(session_id,kb_id)
);
ALTER TABLE ra_document ADD COLUMN IF NOT EXISTS deleted_at BIGINT;
ALTER TABLE ra_document_version ADD COLUMN IF NOT EXISTS target_profile VARCHAR(64);
ALTER TABLE ra_document DROP CONSTRAINT IF EXISTS ra_document_kb_id_name_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_document_live_name ON ra_document(kb_id,name) WHERE deleted_at IS NULL;
CREATE TABLE IF NOT EXISTS ra_file_cleanup (
    storage_key VARCHAR(50) PRIMARY KEY,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0
);
