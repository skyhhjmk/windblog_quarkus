-- liquibase formatted sql

-- changeset biliwind:161-add-media-thumbhash
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS thumbhash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_media_thumbhash ON media (thumbhash);

COMMENT ON COLUMN media.thumbhash IS 'ThumbHash 紧凑二进制编码的 Base64 字符串';

-- rollback ALTER TABLE media DROP COLUMN IF EXISTS thumbhash;
