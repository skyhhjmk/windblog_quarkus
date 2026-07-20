-- liquibase formatted sql

-- changeset biliwind:086-create-email-center
alter table users
    add column if not exists email_verified_at timestamptz;
alter table users
    add column if not exists subscribe_article_updates boolean not null default false;
alter table users
    add column if not exists subscribe_promotions boolean not null default false;
update users
set email_verified_at = now()
where email_verified_at is null
  and deleted_at is null;

create table email_channels
(
    id                 bigserial primary key,
    name               varchar(100) not null unique,
    provider           varchar(32)  not null,
    host               varchar(255) not null,
    port               integer      not null,
    security_mode      varchar(16)  not null,
    username           varchar(255),
    password_encrypted text,
    from_name          varchar(128) not null,
    from_address       varchar(255) not null,
    reply_to_address   varchar(255),
    enabled            boolean      not null default true,
    created_at         timestamptz  not null default now(),
    updated_at         timestamptz  not null default now()
);
create table email_channel_groups
(
    id                 bigserial primary key,
    name               varchar(100) not null unique,
    dispatch_mode      varchar(16)  not null,
    next_channel_index integer      not null default 0,
    enabled            boolean      not null default true
);
create table email_channel_group_members
(
    group_id   bigint  not null references email_channel_groups (id) on delete cascade,
    channel_id bigint  not null references email_channels (id) on delete cascade,
    priority   integer not null,
    primary key (group_id, channel_id)
);
create table email_scenario_routes
(
    scenario         varchar(64) primary key,
    channel_group_id bigint references email_channel_groups (id),
    channel_id       bigint references email_channels (id),
    template_key     varchar(64) not null
);
create table email_verification_tokens
(
    id          bigserial primary key,
    user_id     bigint       not null references users (id) on delete cascade,
    token_hash  varchar(128) not null unique,
    expires_at  timestamptz  not null,
    consumed_at timestamptz,
    created_at  timestamptz  not null default now()
);
create table email_deliveries
(
    id                bigserial primary key,
    scenario          varchar(64)  not null,
    recipient_address varchar(255) not null,
    subject           varchar(255) not null,
    html_content      text         not null,
    channel_group_id  bigint references email_channel_groups (id),
    channel_id        bigint references email_channels (id),
    status            varchar(16)  not null,
    attempt_count     integer      not null default 0,
    next_attempt_at   timestamptz  not null default now(),
    last_error        text,
    created_at        timestamptz  not null default now(),
    sent_at           timestamptz
);
create index idx_email_deliveries_pending on email_deliveries (status, next_attempt_at);
create table email_templates
(
    id               bigserial primary key,
    template_key     varchar(64)   not null unique,
    name             varchar(128)  not null,
    subject_template varchar(255)  not null,
    title            varchar(255)  not null,
    greeting         varchar(255)  not null,
    content          text          not null,
    button_text      varchar(128)  not null,
    button_url       varchar(1024) not null,
    published        boolean       not null default false,
    version          integer       not null default 1,
    updated_at       timestamptz   not null default now()
);
create table email_campaigns
(
    id               bigserial primary key,
    name             varchar(128) not null,
    subject          varchar(255) not null,
    template_id      bigint       not null references email_templates (id),
    channel_group_id bigint references email_channel_groups (id),
    channel_id       bigint references email_channels (id),
    recipient_type   varchar(32)  not null,
    created_at       timestamptz  not null default now(),
    created_by       bigint
);

-- rollback drop table email_deliveries; drop table email_verification_tokens; drop table email_scenario_routes; drop table email_channel_group_members; drop table email_channel_groups; drop table email_channels;
