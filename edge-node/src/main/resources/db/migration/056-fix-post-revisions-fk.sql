-- liquibase formatted sql

-- changeset biliwind:056-fix-post-revisions-fk
-- 1. 清理孤立的版本记录（post_id 在 posts 表中不存在的记录）
DELETE
FROM post_revisions
WHERE post_id NOT IN (SELECT id FROM posts);

-- 2. 删除可能存在的旧约束（避免命名冲突或状态不一致）
ALTER TABLE post_revisions
    DROP CONSTRAINT IF EXISTS fk_revisions_post;

-- 3. 重新创建外键约束，显式指定 ON DELETE CASCADE
ALTER TABLE post_revisions
    ADD CONSTRAINT fk_revisions_post
        FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;

-- rollback alter table post_revisions drop constraint fk_revisions_post;
