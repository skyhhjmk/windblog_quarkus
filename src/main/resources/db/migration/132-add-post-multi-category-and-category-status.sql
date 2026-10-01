-- liquibase formatted sql

-- changeset biliwind:132-add-post-multi-category-and-category-status
ALTER TABLE users
    ADD COLUMN must_reset_password BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE categories
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE post_categories
(
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id, category_id)
);

CREATE INDEX idx_post_categories_category_post
    ON post_categories (category_id, post_id);

INSERT INTO post_categories (post_id, category_id, position)
SELECT id, category_id, 0
FROM posts
WHERE category_id IS NOT NULL
ON CONFLICT DO NOTHING;

ALTER TABLE comments ADD COLUMN guest_name VARCHAR(255);
ALTER TABLE comments ADD COLUMN guest_email VARCHAR(255);

--rollback ALTER TABLE comments DROP COLUMN IF EXISTS guest_email;
--rollback ALTER TABLE comments DROP COLUMN IF EXISTS guest_name;
--rollback DROP TABLE IF EXISTS post_categories;
--rollback ALTER TABLE categories DROP COLUMN IF EXISTS enabled;
--rollback ALTER TABLE users DROP COLUMN IF EXISTS must_reset_password;
