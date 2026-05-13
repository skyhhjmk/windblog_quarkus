-- liquibase formatted sql

-- changeset biliwind:043-add-category-post-count
ALTER TABLE categories
    ADD COLUMN IF NOT EXISTS post_count BIGINT DEFAULT 0 NOT NULL;

COMMENT ON COLUMN categories.post_count IS '该分类下的文章数量（缓存）';

-- rollback ALTER TABLE categories DROP COLUMN IF EXISTS post_count;
