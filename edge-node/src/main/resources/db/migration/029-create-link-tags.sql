-- liquibase formatted sql

-- changeset biliwind:029-create-link-tags
create table link_tags
(
    id          bigserial primary key,
    slug        varchar(160) not null unique,
    name        jsonb        not null,
    description jsonb,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now()
);

comment on table link_tags is '链接标签表';

comment on column link_tags.id is '标签ID';
comment on column link_tags.slug is '唯一slug';
comment on column link_tags.name is '多语言名称 JSONB';
comment on column link_tags.description is '多语言描述 JSONB';
comment on column link_tags.created_at is '创建时间';
comment on column link_tags.updated_at is '更新时间';

-- rollback drop table link_tags;
