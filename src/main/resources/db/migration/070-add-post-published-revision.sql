-- liquibase formatted sql

-- changeset biliwind:070-add-post-published-revision
alter table posts
    add column published_revision_id bigint;

alter table posts
    add constraint fk_posts_published_revision
        foreign key (published_revision_id) references post_revisions (id) on delete set null;

update posts
set published_revision_id = current_revision_id
where status = 1
  and current_revision_id is not null;

create index idx_posts_published_revision_id
    on posts (published_revision_id);

comment on column posts.published_revision_id is '前台公开展示版本ID';

-- rollback drop index if exists idx_posts_published_revision_id;
-- rollback alter table posts drop constraint if exists fk_posts_published_revision;
-- rollback alter table posts drop column if exists published_revision_id;
