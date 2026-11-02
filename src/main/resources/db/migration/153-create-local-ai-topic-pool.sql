-- liquibase formatted sql

-- changeset windblog:153-create-local-ai-topic-pool
-- WindBlog-owned AI topic pool. Used when the administrator selects a normal
-- AI provider / polling group for the "topic" operation, so the topic module
-- no longer depends on Codex Creator (and its HMAC shared secret).
CREATE TABLE ai_topic_pool (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    rationale TEXT,
    recommendation VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'SUGGESTED',
    dedupe_key VARCHAR(64) NOT NULL UNIQUE,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    review_note TEXT,
    post_id BIGINT REFERENCES posts(id) ON DELETE SET NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_ai_topic_pool_status ON ai_topic_pool(status, last_seen_at DESC);
CREATE INDEX idx_ai_topic_pool_last_seen ON ai_topic_pool(last_seen_at DESC, id DESC);

CREATE TABLE ai_topic_pool_seeds (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    query TEXT NOT NULL,
    language VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
    region VARCHAR(64),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_ai_topic_pool_seeds_name ON ai_topic_pool_seeds(name);

CREATE TABLE ai_topic_pool_settings (
    id BIGINT PRIMARY KEY DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    interval_minutes INTEGER NOT NULL DEFAULT 360,
    max_seeds_per_run INTEGER NOT NULL DEFAULT 5,
    max_topics_per_run INTEGER NOT NULL DEFAULT 20,
    promotion_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    promotion_markdown TEXT,
    last_run_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO ai_topic_pool_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE ai_topic_pool IS 'WindBlog 本地 AI 话题池（使用普通 AI 提供商或轮询组时）';
COMMENT ON COLUMN ai_topic_pool.status IS 'SUGGESTED/APPROVED/DISMISSED/WRITING/DRAFT_CREATED/FAILED';

-- rollback DROP TABLE ai_topic_pool_settings;
-- rollback DROP TABLE ai_topic_pool_seeds;
-- rollback DROP TABLE ai_topic_pool;
