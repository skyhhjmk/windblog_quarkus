-- liquibase formatted sql

-- changeset biliwind:170-create-static-cache-stats
CREATE TABLE IF NOT EXISTS static_cache_stats (
    cache_key VARCHAR(512) PRIMARY KEY,
    path VARCHAR(512) NOT NULL,
    lang VARCHAR(32) NOT NULL DEFAULT 'zh-CN',
    region VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
    variant VARCHAR(16) NOT NULL DEFAULT 'full',
    hits BIGINT NOT NULL DEFAULT 0,
    misses BIGINT NOT NULL DEFAULT 0,
    last_hit_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_static_cache_stats_hits
    ON static_cache_stats (hits DESC);
