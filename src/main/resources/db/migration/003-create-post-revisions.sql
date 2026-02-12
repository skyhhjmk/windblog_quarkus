-- liquibase formatted sql

-- changeset biliwind:003-create-post-revisions
create table post_revisions
(
    id               bigserial primary key,
    post_id          bigint      not null,
    title            jsonb       not null,
    content_markdown jsonb       not null,
    editor_type      smallint    not null,
    revision_number  integer     not null,
    created_by       bigint      not null,
    created_at       timestamptz not null default now()
);

comment on table post_revisions is '文章版本表';

comment on column post_revisions.id is '版本ID';
comment on column post_revisions.post_id is '所属文章ID';
comment on column post_revisions.title is '版本标题 JSONB';
comment on column post_revisions.content_markdown is 'Markdown正文 JSONB';
comment on column post_revisions.editor_type is '编辑器类型：0=Markdown 1=HTML';
comment on column post_revisions.revision_number is '版本号递增';
comment on column post_revisions.created_by is '创建人';
comment on column post_revisions.created_at is '创建时间';

-- rollback drop table post_revisions;
