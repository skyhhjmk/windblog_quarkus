-- liquibase formatted sql
-- changeset biliwind:087-add-post-content-declarations

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS content_declarations JSONB;
