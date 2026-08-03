-- Record the elapsed time between access-ticket issuance and a protected download.
ALTER TABLE media_download_event
    ADD COLUMN IF NOT EXISTS ticket_age_ms BIGINT;
