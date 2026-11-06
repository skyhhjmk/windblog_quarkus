-- liquibase formatted sql

-- changeset biliwind:158-add-user-token-version
-- Per-user token generation. Incrementing it invalidates every user JWT issued
-- before the change (logout, password change, password reset).
alter table users
    add column if not exists token_version bigint not null default 0;

comment on column users.token_version is '用户 JWT 代次；递增后旧 token 全部失效';

-- rollback alter table users drop column if exists token_version;
