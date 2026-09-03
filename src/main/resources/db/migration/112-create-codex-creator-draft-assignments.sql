--liquibase formatted sql

--changeset windblog:112-create-codex-creator-draft-assignments
CREATE TABLE codex_creator_draft_assignments (
    id BIGSERIAL PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    language VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    instructions TEXT,
    request_key VARCHAR(64) NOT NULL UNIQUE,
    created_by BIGINT NOT NULL,
    codex_job_id BIGINT,
    codex_task_id BIGINT,
    post_id BIGINT REFERENCES posts(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_codex_draft_assignments_topic ON codex_creator_draft_assignments(topic_id, created_at DESC);
CREATE INDEX idx_codex_draft_assignments_status ON codex_creator_draft_assignments(status, updated_at DESC);

--rollback DROP INDEX idx_codex_draft_assignments_status;
--rollback DROP INDEX idx_codex_draft_assignments_topic;
--rollback DROP TABLE codex_creator_draft_assignments;
