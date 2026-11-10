-- liquibase formatted sql
-- changeset biliwind:164-add-system-settings-level
ALTER TABLE system_settings
    ADD COLUMN IF NOT EXISTS setting_level VARCHAR(32) NOT NULL DEFAULT 'NORMAL';

COMMENT ON COLUMN system_settings.setting_level IS '设置级别：NORMAL(简单常规设置，无验证期), CRITICAL(关键设置/影响站点运行，需验证期)';

UPDATE system_settings
SET setting_level = 'CRITICAL'
WHERE config_key IN ('elasticsearch', 'security_network', 'comment_security');

-- rollback ALTER TABLE system_settings DROP COLUMN IF EXISTS setting_level;
