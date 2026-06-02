-- liquibase formatted sql

-- changeset biliwind:079-add-audit-log-request-context
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS request_method VARCHAR(16);
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS request_path VARCHAR(512);
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS client_ip VARCHAR(128);
ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS user_agent VARCHAR(1024);

COMMENT ON COLUMN audit_logs.request_id IS '请求标识，用于串联同一次管理端操作';
COMMENT ON COLUMN audit_logs.request_method IS '请求方法';
COMMENT ON COLUMN audit_logs.request_path IS '请求路径';
COMMENT ON COLUMN audit_logs.client_ip IS '客户端 IP';
COMMENT ON COLUMN audit_logs.user_agent IS '用户代理字符串';

-- rollback alter table audit_logs drop column if exists request_id;
