-- liquibase formatted sql

-- changeset biliwind:139-add-media-content-sha256
ALTER TABLE media ADD COLUMN content_sha256 varchar(64);
CREATE INDEX idx_media_content_sha256
    ON media (content_sha256)
    WHERE content_sha256 IS NOT NULL AND deleted_at IS NULL;

-- rollback DROP INDEX IF EXISTS idx_media_content_sha256;
-- rollback ALTER TABLE media DROP COLUMN content_sha256;
