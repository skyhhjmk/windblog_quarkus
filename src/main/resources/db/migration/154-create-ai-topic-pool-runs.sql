-- liquibase formatted sql

-- changeset windblog:154-create-ai-topic-pool-runs
-- Asynchronous discovery runs for the WindBlog-owned topic pool. Provider
-- discovery can take longer than the edge timeout, so the admin request only
-- creates the run and returns immediately.
CREATE TABLE ai_topic_pool_runs (
    id BIGSERIAL PRIMARY KEY,
    trigger_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    provider_id BIGINT,
    seed_count INTEGER NOT NULL DEFAULT 0,
    topic_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_ai_topic_pool_runs_status ON ai_topic_pool_runs(status, created_at DESC);
CREATE INDEX idx_ai_topic_pool_runs_created ON ai_topic_pool_runs(created_at DESC, id DESC);

COMMENT ON TABLE ai_topic_pool_runs IS 'WindBlog 本地 AI 话题发现运行记录';
COMMENT ON COLUMN ai_topic_pool_runs.status IS 'QUEUED/RUNNING/SUCCEEDED/FAILED';

-- rollback DROP TABLE ai_topic_pool_runs;
