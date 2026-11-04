-- liquibase formatted sql

-- changeset windblog:156-create-ai-article-draft-runs
-- Asynchronous article drafting with a normal AI provider. The admin request
-- only creates the run and returns immediately so a slow provider cannot trip
-- the public edge timeout (HTTP 554).
CREATE TABLE ai_article_draft_runs (
    id BIGSERIAL PRIMARY KEY,
    topic_id BIGINT NOT NULL,
    post_id BIGINT,
    provider_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    language VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
    instructions TEXT,
    repost_policy_code VARCHAR(64),
    regenerate BOOLEAN NOT NULL DEFAULT FALSE,
    title VARCHAR(512),
    summary TEXT,
    error_message TEXT,
    created_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_ai_article_draft_runs_status ON ai_article_draft_runs(status, created_at DESC);
CREATE INDEX idx_ai_article_draft_runs_created ON ai_article_draft_runs(created_at DESC, id DESC);
CREATE INDEX idx_ai_article_draft_runs_topic ON ai_article_draft_runs(topic_id, created_at DESC);

COMMENT ON TABLE ai_article_draft_runs IS 'WindBlog AI 草稿生成运行记录';
COMMENT ON COLUMN ai_article_draft_runs.status IS 'QUEUED/RUNNING/DRAFT_CREATED/FAILED';

-- rollback DROP TABLE ai_article_draft_runs;
