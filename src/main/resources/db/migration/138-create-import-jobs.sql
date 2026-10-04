--liquibase formatted sql
--changeset windblog:138-create-import-jobs
CREATE TABLE import_jobs (
    id UUID PRIMARY KEY,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    request_payload JSONB NOT NULL,
    sql_artifact_path TEXT,
    operator_id BIGINT NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    progress_payload JSONB,
    result_payload JSONB,
    last_error TEXT,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until TIMESTAMPTZ,
    lock_owner VARCHAR(80),
    heartbeat_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_import_jobs_claim ON import_jobs (status, available_at, created_at);
CREATE INDEX idx_import_jobs_watchdog ON import_jobs (status, heartbeat_at);

--rollback DROP TABLE import_jobs;
