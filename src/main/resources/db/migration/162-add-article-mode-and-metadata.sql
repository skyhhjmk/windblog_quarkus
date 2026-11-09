-- liquibase formatted sql

-- changeset biliwind:162-add-article-mode-and-metadata
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS article_mode SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tutorial_meta JSONB,
    ADD COLUMN IF NOT EXISTS resource_meta JSONB;

CREATE INDEX IF NOT EXISTS idx_posts_article_mode ON posts (article_mode);

COMMENT ON COLUMN posts.article_mode IS '文章模式：0=常规(STANDARD), 1=教程(TUTORIAL), 2=资源(RESOURCE)';
COMMENT ON COLUMN posts.tutorial_meta IS '教程模式元数据 (JSONB)';
COMMENT ON COLUMN posts.resource_meta IS '资源模式元数据 (JSONB)';

-- rollback ALTER TABLE posts DROP COLUMN IF EXISTS article_mode, DROP COLUMN IF EXISTS tutorial_meta, DROP COLUMN IF EXISTS resource_meta;
