-- liquibase formatted sql

-- changeset biliwind:006-create-tags
create table tags
(
    id          bigserial primary key,
    slug        varchar(160) not null unique,
    name        jsonb        not null,
    description jsonb,
    created_at  timestamptz  not null default now()
);

comment on table tags is '标签表';

comment on column tags.id is '标签ID';
comment on column tags.slug is '唯一slug';
comment on column tags.name is '多语言名称 JSONB';
comment on column tags.description is '多语言描述 JSONB';
comment on column tags.created_at is '创建时间';

-- rollback drop table tags;
