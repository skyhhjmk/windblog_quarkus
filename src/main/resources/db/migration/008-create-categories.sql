-- liquibase formatted sql

-- changeset biliwind:008-create-categories
-- description: Create categories table using ltree for hierarchical path

------------------------------------------------------------
-- Enable extension (safe even if already enabled)
------------------------------------------------------------
create extension if not exists ltree;

------------------------------------------------------------
-- Table
------------------------------------------------------------
create table categories
(
    id          bigserial primary key,
    parent_id   bigint references categories(id) on delete set null,
    slug        varchar(160) not null unique,
    name        jsonb        not null,
    description jsonb,
    path        ltree        not null,
    created_at  timestamptz  not null default now()
);

------------------------------------------------------------
-- Indexes
------------------------------------------------------------
create unique index uq_categories_path
    on categories(path);

create index idx_categories_path_gist
    on categories using gist(path);

------------------------------------------------------------
-- Comments
------------------------------------------------------------
comment on table categories is '分类表（使用ltree存储层级路径）';

comment on column categories.id is '分类ID';
comment on column categories.parent_id is '父级分类ID';
comment on column categories.slug is '唯一slug';
comment on column categories.name is '多语言名称 JSONB';
comment on column categories.description is '多语言描述 JSONB';
comment on column categories.path is '分类层级路径（ltree格式，例如 tech.backend.java）';
comment on column categories.created_at is '创建时间';

------------------------------------------------------------
-- rollback
------------------------------------------------------------
-- rollback drop table categories;
