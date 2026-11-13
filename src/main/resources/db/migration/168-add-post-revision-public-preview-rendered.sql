-- liquibase formatted sql

-- changeset biliwind:168-add-post-revision-public-preview-rendered
ALTER TABLE post_revisions
    ADD COLUMN IF NOT EXISTS public_preview_rendered BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN post_revisions.public_preview_rendered IS 'Edge sync provenance: content_markdown already holds the sanitized public preview rendered by the primary';

-- rollback ALTER TABLE post_revisions DROP COLUMN IF EXISTS public_preview_rendered;
