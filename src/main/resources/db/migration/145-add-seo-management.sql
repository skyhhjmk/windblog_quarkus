-- liquibase formatted sql
-- changeset windblog:145-add-seo-management
CREATE TABLE IF NOT EXISTS seo_redirects (
    id BIGSERIAL PRIMARY KEY,
    source VARCHAR(512) NOT NULL UNIQUE,
    target VARCHAR(512) NOT NULL,
    status INTEGER NOT NULL DEFAULT 301 CHECK (status IN (301,302,307,308)),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS seo_post_policies (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL UNIQUE REFERENCES posts(id) ON DELETE CASCADE,
    noindex BOOLEAN NOT NULL DEFAULT FALSE,
    nofollow BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS seo_not_found (
    path VARCHAR(512) PRIMARY KEY,
    referer VARCHAR(512),
    hits BIGINT NOT NULL DEFAULT 1,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_seo_not_found_last_seen ON seo_not_found(last_seen DESC);
INSERT INTO system_settings(config_key, config_value, config_type, group_name, ui_schema, description)
VALUES ('seo', '{"google_site_verification":"","index_enabled":true,"follow_enabled":true}', 'object', 'seo',
'{"type":"object","fields":[{"key":"google_site_verification","widget":"input","label":"Google Search Console 验证码"},{"key":"index_enabled","widget":"switch","label":"允许搜索引擎收录公开页面"},{"key":"follow_enabled","widget":"switch","label":"允许跟踪页面链接"}]}', 'SEO 收录控制与站点验证')
ON CONFLICT (config_key) DO NOTHING;
