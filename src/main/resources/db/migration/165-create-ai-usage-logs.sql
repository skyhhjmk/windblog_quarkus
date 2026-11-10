-- liquibase formatted sql
-- changeset biliwind:165-create-ai-usage-logs
CREATE TABLE IF NOT EXISTS ai_usage_logs (
    id BIGSERIAL PRIMARY KEY,
    provider_config_id BIGINT,
    provider_name VARCHAR(64) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    model VARCHAR(128),
    feature VARCHAR(64) NOT NULL,
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT TRUE,
    error_message VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_logs_created_at ON ai_usage_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_usage_logs_provider_config ON ai_usage_logs (provider_config_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_usage_logs_feature ON ai_usage_logs (feature, created_at DESC);

-- rollback DROP TABLE IF EXISTS ai_usage_logs;
