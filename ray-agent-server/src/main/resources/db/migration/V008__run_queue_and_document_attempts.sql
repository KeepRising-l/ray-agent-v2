CREATE UNIQUE INDEX IF NOT EXISTS ra_one_active_run ON ra_run(session_id)
    WHERE status IN ('CREATED','QUEUED','RUNNING','WAITING_USER');
CREATE TABLE IF NOT EXISTS ra_run_schedule (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES ra_run(id),
    owner_id VARCHAR(100) NOT NULL,
    phase INT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    enqueued_at BIGINT NOT NULL,
    started_at BIGINT
);
CREATE TABLE IF NOT EXISTS ra_ingestion_attempt (
    version_id VARCHAR(36) NOT NULL REFERENCES ra_document_version(id),
    attempt INT NOT NULL,
    stage VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    error_code VARCHAR(50),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    started_at BIGINT NOT NULL,
    ended_at BIGINT,
    PRIMARY KEY(version_id,attempt)
);
CREATE TABLE IF NOT EXISTS ra_document_retry (
    version_id VARCHAR(36) PRIMARY KEY REFERENCES ra_document_version(id),
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT,
    reason VARCHAR(50)
);
