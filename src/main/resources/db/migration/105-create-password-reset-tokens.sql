--liquibase formatted sql

--changeset biliwind:105-create-password-reset-tokens
create table password_reset_tokens
(
    id          bigserial primary key,
    user_id     bigint       not null references users (id) on delete cascade,
    token_hash  varchar(128) not null unique,
    expires_at  timestamptz  not null,
    consumed_at timestamptz,
    created_at  timestamptz  not null default now()
);

create index idx_password_reset_tokens_active
    on password_reset_tokens (user_id, consumed_at, expires_at);

--rollback drop table password_reset_tokens;
