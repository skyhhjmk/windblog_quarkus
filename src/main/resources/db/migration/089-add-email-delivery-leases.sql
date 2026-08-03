ALTER TABLE email_deliveries ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE email_deliveries ADD COLUMN IF NOT EXISTS lock_owner VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_email_deliveries_lease ON email_deliveries (status, locked_until, next_attempt_at);
