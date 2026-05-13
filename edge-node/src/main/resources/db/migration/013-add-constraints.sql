-- liquibase formatted sql

-- changeset biliwind:013-add-constraints

-- revision_number 在同一文章内唯一
alter table post_revisions
    add constraint uq_post_revision_number
        unique (post_id, revision_number);

-- slug 唯一
alter table posts
    add constraint uq_posts_slug unique (slug);

alter table tags
    add constraint uq_tags_slug unique (slug);

alter table categories
    add constraint uq_categories_slug unique (slug);

-- rollback

alter table categories
    drop constraint uq_categories_slug;
alter table tags
    drop constraint uq_tags_slug;
alter table posts
    drop constraint uq_posts_slug;
alter table post_revisions
    drop constraint uq_post_revision_number;
