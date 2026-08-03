CREATE TABLE IF NOT EXISTS media_download_event (
    id BIGSERIAL PRIMARY KEY,
    media_id BIGINT NOT NULL,
    post_id BIGINT NOT NULL,
    ticket_id BIGINT,
    subject_hash VARCHAR(128),
    ip_hash VARCHAR(128),
    ua_hash VARCHAR(128),
    bytes_sent BIGINT,
    status VARCHAR(32) NOT NULL,
    deny_reason VARCHAR(128),
    node_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_media_download_event_media_time
    ON media_download_event (media_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_download_event_ticket_time
    ON media_download_event (ticket_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_download_event_status_time
    ON media_download_event (status, created_at DESC);
