-- liquibase formatted sql

-- changeset biliwind:011-add-foreign-keys

-- posts
alter table posts
    add constraint fk_posts_user
        foreign key (user_id) references users (id) on delete restrict;

alter table posts
    add constraint fk_posts_current_revision
        foreign key (current_revision_id) references post_revisions (id) on delete set null;

-- post_revisions
alter table post_revisions
    add constraint fk_revisions_post
        foreign key (post_id) references posts (id) on delete cascade;

alter table post_revisions
    add constraint fk_revisions_user
        foreign key (created_by) references users (id) on delete restrict;

-- post_media
alter table post_media
    add constraint fk_post_media_post
        foreign key (post_id) references posts (id) on delete cascade;

alter table post_media
    add constraint fk_post_media_media
        foreign key (media_id) references media (id) on delete cascade;

-- post_tags
alter table post_tags
    add constraint fk_post_tags_post
        foreign key (post_id) references posts (id) on delete cascade;

alter table post_tags
    add constraint fk_post_tags_tag
        foreign key (tag_id) references tags (id) on delete cascade;

-- categories
alter table categories
    add constraint fk_categories_parent
        foreign key (parent_id) references categories (id) on delete set null;

-- comments
alter table comments
    add constraint fk_comments_post
        foreign key (post_id) references posts (id) on delete cascade;

alter table comments
    add constraint fk_comments_parent
        foreign key (parent_id) references comments (id) on delete cascade;

alter table comments
    add constraint fk_comments_user
        foreign key (user_id) references users (id) on delete set null;

-- audit_logs
alter table audit_logs
    add constraint fk_audit_user
        foreign key (performed_by) references users (id) on delete set null;

-- rollback

alter table audit_logs
    drop constraint fk_audit_user;

alter table comments
    drop constraint fk_comments_user;
alter table comments
    drop constraint fk_comments_parent;
alter table comments
    drop constraint fk_comments_post;

alter table categories
    drop constraint fk_categories_parent;

alter table post_tags
    drop constraint fk_post_tags_tag;
alter table post_tags
    drop constraint fk_post_tags_post;

alter table post_media
    drop constraint fk_post_media_media;
alter table post_media
    drop constraint fk_post_media_post;

alter table post_revisions
    drop constraint fk_revisions_user;
alter table post_revisions
    drop constraint fk_revisions_post;

alter table posts
    drop constraint fk_posts_current_revision;
alter table posts
    drop constraint fk_posts_user;
