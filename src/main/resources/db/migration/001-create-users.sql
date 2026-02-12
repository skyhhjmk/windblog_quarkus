-- liquibase formatted sql

-- changeset biliwind:001-create-users
create table users
(
    id          bigserial primary key,
    username    varchar(100) not null unique,
    email       varchar(255) not null unique,
    password    varchar(255) not null,
    status      smallint     not null default 1,
    created_at  timestamptz  not null default now(),
    updated_at  timestamptz  not null default now(),
    deleted_at  timestamptz
);

comment on table users is '用户表';

comment on column users.id is '用户ID';
comment on column users.username is '用户名';
comment on column users.email is '邮箱';
comment on column users.password is '加密后的密码';
comment on column users.status is '用户状态：0=禁用 1=正常';
comment on column users.created_at is '创建时间';
comment on column users.updated_at is '更新时间';
comment on column users.deleted_at is '软删除时间';

-- rollback drop table users;
