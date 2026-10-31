CREATE TABLE IF NOT EXISTS ra_session_summary (
    session_id VARCHAR(36) PRIMARY KEY REFERENCES ra_session(id),
    revision INT NOT NULL,
    covered_run_ids TEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ra_user_question (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES ra_run(id),
    owner_id VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    question VARCHAR(600) NOT NULL,
    checkpoint TEXT NOT NULL,
    answer TEXT,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    answered_at BIGINT
);
CREATE UNIQUE INDEX IF NOT EXISTS ra_one_waiting_question ON ra_user_question(run_id) WHERE status='WAITING_USER';
CREATE UNIQUE INDEX IF NOT EXISTS ra_one_assistant_message ON ra_message(run_id) WHERE role='ASSISTANT';
