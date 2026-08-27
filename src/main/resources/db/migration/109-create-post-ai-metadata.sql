--liquibase formatted sql

--changeset windblog:109-create-post-ai-metadata
CREATE TABLE IF NOT EXISTS post_ai_metadata (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL,
    revision_id BIGINT,
    task_id VARCHAR(128),
    operation VARCHAR(80) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    model_id VARCHAR(160),
    reasoning_effort VARCHAR(32),
    generation_mode VARCHAR(32) NOT NULL,
    provenance JSONB NOT NULL DEFAULT '{}'::jsonb,
    auto_publish_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_post_ai_metadata_post ON post_ai_metadata(post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_ai_metadata_operation ON post_ai_metadata(operation, created_at DESC);

--rollback DROP TABLE post_ai_metadata;
