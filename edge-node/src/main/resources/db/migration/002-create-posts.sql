-- liquibase formatted sql

-- changeset biliwind:002-create-posts
create table posts
(
    id                  bigserial primary key,
    slug                varchar(160) not null unique,
    title               jsonb        not null,
    summary             jsonb,
    ai_summary          jsonb,
    current_revision_id bigint,
    status              smallint     not null default 0,
    visibility          smallint     not null default 0,
    user_id             bigint       not null,
    published_at        timestamptz,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),
    deleted_at          timestamptz,
    version             integer      not null default 0
);

comment on table posts is '文章主表，仅存当前发布状态';

comment on column posts.id is '文章ID';
comment on column posts.slug is '文章唯一标识';
comment on column posts.title is '多语言标题 JSONB';
comment on column posts.summary is '多语言摘要 JSONB';
comment on column posts.ai_summary is '多语言AI摘要 JSONB';
comment on column posts.current_revision_id is '当前生效版本ID';
comment on column posts.status is '文章状态：0=草稿 1=发布 2=归档';
comment on column posts.visibility is '可见性：0=公开 1=私密 2=密码';
comment on column posts.user_id is '作者ID';
comment on column posts.published_at is '发布时间';
comment on column posts.created_at is '创建时间';
comment on column posts.updated_at is '更新时间';
comment on column posts.deleted_at is '软删除时间';
comment on column posts.version is '乐观锁版本号';

-- rollback drop table posts;
