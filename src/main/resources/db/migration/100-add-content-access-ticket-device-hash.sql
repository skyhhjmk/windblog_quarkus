ALTER TABLE content_access_ticket
    ADD COLUMN IF NOT EXISTS device_hash VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_content_access_ticket_device
    ON content_access_ticket (device_hash, scope, revoked_at);
