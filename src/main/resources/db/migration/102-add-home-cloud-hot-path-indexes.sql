-- Keep public reads, protected downloads and durable workers bounded on the
-- PostgreSQL path. Every index is additive and safe to re-run.

CREATE INDEX IF NOT EXISTS idx_posts_public_published_at
    ON posts (published_at DESC, id DESC)
    WHERE deleted_at IS NULL AND status = 1 AND published_revision_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_posts_visibility_status_deleted
    ON posts (visibility, status, deleted_at, published_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_post_revisions_post_revision
    ON post_revisions (post_id, revision_number DESC);

CREATE INDEX IF NOT EXISTS idx_post_media_post_usage
    ON post_media (post_id, usage_type, media_id);

CREATE INDEX IF NOT EXISTS idx_post_media_media_usage
    ON post_media (media_id, usage_type, post_id);

CREATE INDEX IF NOT EXISTS idx_comments_post_status_created
    ON comments (post_id, status, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_storage_key_active
    ON media (storage_key)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_content_access_ticket_scope_expiry
    ON content_access_ticket (scope, post_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_download_event_post_time
    ON media_download_event (post_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_media_download_event_subject_time
    ON media_download_event (subject_hash, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_outbox_claim_ready
    ON outbox_events (available_at, id)
    WHERE status IN ('PENDING', 'IN_FLIGHT');
