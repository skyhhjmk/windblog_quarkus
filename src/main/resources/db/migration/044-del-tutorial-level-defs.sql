-- liquibase formatted sql

-- changeset biliwind:042-remove-tutorial-block-fields
ALTER TABLE posts
DROP COLUMN IF EXISTS tutorial_level_defs;

ALTER TABLE post_revisions
DROP COLUMN IF EXISTS content_blocks,
    DROP COLUMN IF EXISTS tutorial_level_defs;

-- rollback ALTER TABLE posts ADD COLUMN tutorial_level_defs jsonb;
-- rollback COMMENT ON COLUMN posts.tutorial_level_defs IS '每篇文章的教程级别自定义配置 (JSONB)';
-- rollback ALTER TABLE post_revisions ADD COLUMN content_blocks jsonb, ADD COLUMN tutorial_level_defs jsonb;
-- rollback COMMENT ON COLUMN post_revisions.content_blocks IS '块结构多级别教程正文 (JSONB)';
-- rollback COMMENT ON COLUMN post_revisions.tutorial_level_defs IS '保存版本时的教程级别自定义配置 (JSONB)';