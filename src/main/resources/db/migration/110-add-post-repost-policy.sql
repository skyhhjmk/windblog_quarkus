-- liquibase formatted sql
-- changeset biliwind:110-add-post-repost-policy

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS repost_policy_code VARCHAR(64);
