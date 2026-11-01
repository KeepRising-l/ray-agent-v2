CREATE TABLE IF NOT EXISTS ra_admin_lock (id INT PRIMARY KEY);
INSERT INTO ra_admin_lock VALUES (1) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS ra_admin_settings (
    id VARCHAR(80) PRIMARY KEY, revision BIGINT NOT NULL, payload TEXT NOT NULL,
    actor VARCHAR(80) NOT NULL, updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS ra_admin_audit (
    id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(100) NOT NULL, actor VARCHAR(80) NOT NULL,
    action VARCHAR(60) NOT NULL, target VARCHAR(100) NOT NULL, request_id VARCHAR(80) NOT NULL,
    status VARCHAR(30) NOT NULL, code VARCHAR(80), result_id VARCHAR(100),
    created_at BIGINT NOT NULL, ended_at BIGINT,
    UNIQUE(owner_id,action,request_id)
);
CREATE TABLE IF NOT EXISTS ra_run_snapshot (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES ra_run(id), owner_id VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL, created_at BIGINT NOT NULL
);
