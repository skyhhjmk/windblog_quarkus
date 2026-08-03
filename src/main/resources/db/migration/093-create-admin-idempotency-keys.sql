CREATE TABLE IF NOT EXISTS admin_idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(160) NOT NULL,
    resource VARCHAR(160) NOT NULL,
    action VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_admin_idempotency_user_key UNIQUE (user_id, request_key)
);

CREATE INDEX IF NOT EXISTS idx_admin_idempotency_expiry
    ON admin_idempotency_keys (expires_at);
