-- liquibase formatted sql

-- changeset windblog:101-create-admin-revoked-tokens
CREATE TABLE IF NOT EXISTS admin_revoked_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reason VARCHAR(255),
    CONSTRAINT fk_admin_revoked_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_revoked_tokens_expires_at
    ON admin_revoked_tokens (expires_at);

CREATE INDEX IF NOT EXISTS idx_admin_revoked_tokens_user_id
    ON admin_revoked_tokens (user_id);
