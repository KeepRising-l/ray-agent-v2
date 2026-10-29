CREATE TABLE IF NOT EXISTS ra_session (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(100) NOT NULL,
    title VARCHAR(100) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ra_run (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES ra_session(id),
    owner_id VARCHAR(100) NOT NULL,
    client_request_id VARCHAR(100) NOT NULL,
    channel_id VARCHAR(100) NOT NULL,
    configured_model VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at BIGINT NOT NULL,
    ended_at BIGINT,
    estimated_input INTEGER,
    input_tokens INTEGER,
    output_tokens INTEGER,
    error_message VARCHAR(500),
    UNIQUE(session_id, client_request_id)
);
CREATE TABLE IF NOT EXISTS ra_message (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL REFERENCES ra_session(id),
    run_id VARCHAR(36) NOT NULL REFERENCES ra_run(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    complete BOOLEAN NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ra_event (
    run_id VARCHAR(36) NOT NULL REFERENCES ra_run(id),
    seq INTEGER NOT NULL,
    type VARCHAR(50) NOT NULL,
    detail TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY(run_id, seq)
);
-- R03 事件保存有限的结构化工具元数据和来源；兼容已经存在的 R02 表。
ALTER TABLE ra_event ALTER COLUMN detail TYPE TEXT;
CREATE INDEX IF NOT EXISTS idx_session_owner ON ra_session(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_run_session ON ra_run(session_id, created_at);
CREATE INDEX IF NOT EXISTS idx_message_session ON ra_message(session_id, created_at);
