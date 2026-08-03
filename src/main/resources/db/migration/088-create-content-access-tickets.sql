CREATE TABLE IF NOT EXISTS content_access_ticket (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    subject_type VARCHAR(32) NOT NULL,
    subject_id BIGINT,
    post_id BIGINT,
    scope VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    key_version INTEGER NOT NULL DEFAULT 1,
    last_used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_access_ticket_lookup
    ON content_access_ticket (token_hash, scope, expires_at);
CREATE INDEX IF NOT EXISTS idx_content_access_ticket_subject
    ON content_access_ticket (subject_id, scope, revoked_at);
