-- liquibase formatted sql

-- changeset biliwind:163-remove-event-log-foreign-keys
-- 移除高频事件日志、流水、风控与监控分析表的物理外键约束，降低写入锁竞争与级联死锁风险，
-- 同时保留并补齐所有关联列上的 B-tree 索引以保障联查性能。

-- 1. 转载访问追踪事件 (repost_referral_events)
ALTER TABLE IF EXISTS repost_referral_events
    DROP CONSTRAINT IF EXISTS fk_repost_referral_events_license,
    DROP CONSTRAINT IF EXISTS fk_repost_referral_events_post,
    DROP CONSTRAINT IF EXISTS fk_repost_referral_events_article;

CREATE INDEX IF NOT EXISTS idx_repost_referral_events_article_id ON repost_referral_events (article_id);

-- 2. 短链重定向点击流水 (redirect_click_events)
ALTER TABLE IF EXISTS redirect_click_events
    DROP CONSTRAINT IF EXISTS fk_redirect_click_events_token,
    DROP CONSTRAINT IF EXISTS fk_redirect_click_events_license;

CREATE INDEX IF NOT EXISTS idx_redirect_click_events_token ON redirect_click_events (affiliate_token_id);
CREATE INDEX IF NOT EXISTS idx_redirect_click_events_license ON redirect_click_events (repost_license_id);

-- 3. 可疑转载聚合记录 (suspicious_reposts)
ALTER TABLE IF EXISTS suspicious_reposts
    DROP CONSTRAINT IF EXISTS fk_suspicious_reposts_token,
    DROP CONSTRAINT IF EXISTS fk_suspicious_reposts_license;

CREATE INDEX IF NOT EXISTS idx_suspicious_reposts_token ON suspicious_reposts (affiliate_token_id);
CREATE INDEX IF NOT EXISTS idx_suspicious_reposts_license ON suspicious_reposts (repost_license_id);
CREATE INDEX IF NOT EXISTS idx_suspicious_reposts_article ON suspicious_reposts (article_id);

-- 4. 友链 AI 审核记录 (link_audit)
ALTER TABLE IF EXISTS link_audit
    DROP CONSTRAINT IF EXISTS link_audit_link_id_fkey;

CREATE INDEX IF NOT EXISTS idx_link_audit_link_id ON link_audit (link_id);

-- 5. 友链监控日志 (link_monitor_log)
ALTER TABLE IF EXISTS link_monitor_log
    DROP CONSTRAINT IF EXISTS link_monitor_log_link_id_fkey;

CREATE INDEX IF NOT EXISTS idx_link_monitor_log_link_id ON link_monitor_log (link_id);

-- 6. AI 选题池 (ai_topic_pool)
ALTER TABLE IF EXISTS ai_topic_pool
    DROP CONSTRAINT IF EXISTS ai_topic_pool_post_id_fkey;

CREATE INDEX IF NOT EXISTS idx_ai_topic_pool_post_id ON ai_topic_pool (post_id);
