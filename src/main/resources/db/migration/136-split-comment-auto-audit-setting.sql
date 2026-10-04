-- liquibase formatted sql
-- changeset biliwind:136-split-comment-auto-audit-setting
-- Preserve the legacy automatic-audit switch while separating it from automatic decisions.
UPDATE system_settings
SET config_value = jsonb_set(
        config_value,
        '{autoAudit}',
        COALESCE(config_value->'autoAudit', config_value->'auto_audit', 'true'::jsonb),
        true
    )
WHERE config_key = 'ai_comment_audit'
  AND jsonb_typeof(config_value) = 'object';
