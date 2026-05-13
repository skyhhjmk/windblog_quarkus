-- liquibase formatted sql

-- changeset biliwind:046-refactor-audit-logs
-- 1. 重构审计日志表，将非核心业务字段收纳进 ext_info
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS duration_ms;
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS input_tokens;
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS output_tokens;
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS total_tokens;
ALTER TABLE audit_logs
    ADD COLUMN ext_info JSONB;
-- 修改 entity_id 为 VARCHAR 以支持字符串主键
ALTER TABLE audit_logs
    ALTER COLUMN entity_id TYPE VARCHAR(255);

COMMENT ON COLUMN audit_logs.ext_info IS '扩展信息，用于存储 AI Token、IP 地址、TraceID 等非核心业务字段';
COMMENT ON COLUMN audit_logs.entity_id IS '实体标识（支持数字 ID 或字符串 Key）';
