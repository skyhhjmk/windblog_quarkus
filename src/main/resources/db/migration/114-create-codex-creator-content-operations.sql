--liquibase formatted sql

--changeset windblog:114-create-codex-creator-content-operations
CREATE TABLE IF NOT EXISTS codex_creator_integration_nonces (
    nonce VARCHAR(160) PRIMARY KEY,
    client_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_codex_creator_integration_nonces_expiry
    ON codex_creator_integration_nonces (expires_at);

CREATE TABLE IF NOT EXISTS codex_creator_content_operations (
    id BIGSERIAL PRIMARY KEY,
    request_key VARCHAR(256) NOT NULL UNIQUE,
    operation VARCHAR(64) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PROCESSING',
    response JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_codex_creator_content_operations_status
    ON codex_creator_content_operations (status, updated_at);

--rollback DROP INDEX IF EXISTS idx_codex_creator_content_operations_status;
--rollback DROP TABLE IF EXISTS codex_creator_content_operations;
--rollback DROP INDEX IF EXISTS idx_codex_creator_integration_nonces_expiry;
--rollback DROP TABLE IF EXISTS codex_creator_integration_nonces;
