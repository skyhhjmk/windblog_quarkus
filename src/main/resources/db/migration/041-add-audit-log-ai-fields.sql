-- Add AI-related fields to audit_logs table
ALTER TABLE audit_logs
    ADD COLUMN duration_ms BIGINT;
ALTER TABLE audit_logs
    ADD COLUMN input_tokens INTEGER;
ALTER TABLE audit_logs
    ADD COLUMN output_tokens INTEGER;
ALTER TABLE audit_logs
    ADD COLUMN total_tokens INTEGER;
