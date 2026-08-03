CREATE TABLE IF NOT EXISTS media_reference_rebuild_jobs (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    last_post_id BIGINT,
    posts_scanned BIGINT NOT NULL DEFAULT 0,
    references_created BIGINT NOT NULL DEFAULT 0,
    unreferenced_media BIGINT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_media_reference_rebuild_jobs_status
    ON media_reference_rebuild_jobs (status, updated_at);
