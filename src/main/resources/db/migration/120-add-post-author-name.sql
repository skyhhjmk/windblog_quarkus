--liquibase formatted sql

-- changeset biliwind:120-add-post-author-name
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS author_name VARCHAR(200);

COMMENT ON COLUMN posts.author_name IS '可选的公开作者显示名，例如 AI 模型名称；为空时使用用户名称';

-- rollback ALTER TABLE posts DROP COLUMN IF EXISTS author_name;
