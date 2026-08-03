ALTER TABLE media_download_event
    ADD COLUMN IF NOT EXISTS referrer_hash VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_media_download_event_referrer_time
    ON media_download_event (referrer_hash, created_at DESC);
