-- liquibase formatted sql

-- changeset biliwind:012-add-indexes

-- posts
create index idx_posts_status on posts (status);
create index idx_posts_published_at on posts (published_at);
create index idx_posts_user on posts (user_id);

-- revisions
create index idx_revisions_post on post_revisions (post_id);
create index idx_revisions_created_at on post_revisions (created_at);

-- tags
create index idx_tags_slug on tags (slug);

-- categories
create index idx_categories_parent on categories (parent_id);
create index idx_categories_path on categories (path);

-- comments
create index idx_comments_post on comments (post_id);
create index idx_comments_parent on comments (parent_id);
create index idx_comments_status on comments (status);

-- audit
create index idx_audit_entity on audit_logs (entity_type, entity_id);
create index idx_audit_created_at on audit_logs (created_at);

-- rollback

drop index idx_audit_created_at;
drop index idx_audit_entity;

drop index idx_comments_status;
drop index idx_comments_parent;
drop index idx_comments_post;

drop index idx_categories_path;
drop index idx_categories_parent;

drop index idx_tags_slug;

drop index idx_revisions_created_at;
drop index idx_revisions_post;

drop index idx_posts_user;
drop index idx_posts_published_at;
drop index idx_posts_status;
