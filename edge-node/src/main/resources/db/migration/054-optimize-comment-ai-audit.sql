-- liquibase formatted sql
-- changeset biliwind:054

-- Drop old AI audit columns
ALTER TABLE comments
    DROP COLUMN IF EXISTS ai_duration_ms;
ALTER TABLE comments
    DROP COLUMN IF EXISTS ai_total_tokens;
ALTER TABLE comments
    DROP COLUMN IF EXISTS ai_score;
ALTER TABLE comments
    DROP COLUMN IF EXISTS audit_reason;
ALTER TABLE comments
    DROP COLUMN IF EXISTS audit_type;

-- Add new columns
ALTER TABLE comments
    ADD COLUMN ai_review_data JSONB;
ALTER TABLE comments
    ADD COLUMN is_reviewing BOOLEAN NOT NULL DEFAULT FALSE;
