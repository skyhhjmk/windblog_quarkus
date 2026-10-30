-- liquibase formatted sql

-- changeset biliwind:150-add-user-repost-ban
alter table users
    add column if not exists repost_banned boolean not null default false;
alter table users
    add column if not exists repost_ban_reason text;
alter table users
    add column if not exists repost_banned_at timestamp with time zone;

comment on column users.repost_banned is '是否禁止申请或登记新的转载授权';
comment on column users.repost_ban_reason is '转载权限封禁原因';
comment on column users.repost_banned_at is '转载权限封禁时间';

create index if not exists idx_users_repost_banned on users (repost_banned);

-- rollback drop index if exists idx_users_repost_banned;
-- rollback alter table users drop column if exists repost_banned_at;
-- rollback alter table users drop column if exists repost_ban_reason;
-- rollback alter table users drop column if exists repost_banned;
