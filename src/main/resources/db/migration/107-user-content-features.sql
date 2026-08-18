--liquibase formatted sql

--changeset biliwind:107-user-content-features
ALTER TABLE posts ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMPTZ;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS reviewed_by BIGINT;
ALTER TABLE posts ADD COLUMN IF NOT EXISTS review_note TEXT;

ALTER TABLE posts
    ADD CONSTRAINT fk_posts_reviewed_by
    FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_posts_status_submitted_at
    ON posts (status, submitted_at DESC);

CREATE TABLE IF NOT EXISTS user_post_favorites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_user_post_favorite UNIQUE (user_id, post_id)
);

CREATE INDEX IF NOT EXISTS idx_user_post_favorites_user_created
    ON user_post_favorites (user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS user_notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    target_url VARCHAR(512),
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_user_notifications_user_read_created
    ON user_notifications (user_id, read_at, created_at DESC);

--rollback DROP TABLE IF EXISTS user_notifications;
--rollback DROP TABLE IF EXISTS user_post_favorites;
--rollback ALTER TABLE posts DROP CONSTRAINT IF EXISTS fk_posts_reviewed_by;
--rollback ALTER TABLE posts DROP COLUMN IF EXISTS review_note;
--rollback ALTER TABLE posts DROP COLUMN IF EXISTS reviewed_by;
--rollback ALTER TABLE posts DROP COLUMN IF EXISTS reviewed_at;
--rollback ALTER TABLE posts DROP COLUMN IF EXISTS submitted_at;
