-- liquibase formatted sql

-- changeset biliwind:169-create-user-profile-reviews
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS bio TEXT;

CREATE TABLE IF NOT EXISTS user_profile_reviews (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    old_nickname VARCHAR(100),
    new_nickname VARCHAR(100),
    old_bio TEXT,
    new_bio TEXT,
    old_avatar VARCHAR(512),
    pending_avatar_path VARCHAR(512),
    pending_avatar_mime VARCHAR(64),
    pending_avatar_size BIGINT,
    ai_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN',
    ai_is_safe BOOLEAN,
    ai_score INTEGER,
    ai_reason TEXT,
    ai_raw_response TEXT,
    review_note TEXT,
    reviewed_by BIGINT,
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_profile_reviews_user_status
    ON user_profile_reviews (user_id, status);

CREATE INDEX IF NOT EXISTS idx_user_profile_reviews_status_created
    ON user_profile_reviews (status, created_at DESC);
