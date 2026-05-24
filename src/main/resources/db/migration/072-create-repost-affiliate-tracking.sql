-- liquibase formatted sql

-- changeset biliwind:072-create-repost-affiliate-tracking
create table affiliate_links
(
    id            bigserial primary key,
    name          varchar(200)             not null,
    target_url    text                     not null,
    target_domain varchar(255)             not null,
    status        smallint                 not null default 1,
    note          text,
    created_at    timestamp with time zone not null default now(),
    updated_at    timestamp with time zone not null default now()
);

create table repost_licenses
(
    id                        bigserial primary key,
    code                      varchar(64)              not null,
    article_id                bigint                   not null,
    viewer_user_id            bigint                   not null,
    allowed_domain            varchar(255)             not null,
    target_url                text                     not null,
    status                    smallint                 not null default 1,
    semantic_watermark_config jsonb,
    created_at                timestamp with time zone not null default now(),
    updated_at                timestamp with time zone not null default now(),
    revoked_at                timestamp with time zone
);

create table affiliate_tokens
(
    id                bigserial primary key,
    token_hash        varchar(128)             not null,
    short_display     varchar(32)              not null,
    token_type        varchar(32)              not null,
    status            smallint                 not null default 1,
    article_id        bigint,
    affiliate_link_id bigint,
    viewer_user_id    bigint,
    repost_license_id bigint,
    allowed_domain    varchar(255),
    created_at        timestamp with time zone not null default now(),
    updated_at        timestamp with time zone not null default now(),
    revoked_at        timestamp with time zone
);

create table redirect_click_events
(
    id                 bigserial primary key,
    event_key          varchar(80)              not null,
    affiliate_token_id bigint                   not null,
    repost_license_id  bigint,
    referer_url        text,
    referer_domain     varchar(255),
    referer_category   varchar(40)              not null,
    ip_hash            varchar(128),
    user_agent_hash    varchar(128),
    device_risk_id     varchar(128),
    risk_score         integer                  not null default 0,
    source_node_id     varchar(100),
    clicked_at         timestamp with time zone not null,
    synced_to_primary  boolean                  not null default false
);

create table suspicious_reposts
(
    id                 bigserial primary key,
    affiliate_token_id bigint                   not null,
    repost_license_id  bigint,
    article_id         bigint,
    suspicious_domain  varchar(255)             not null,
    first_referer_url  text,
    latest_referer_url text,
    click_count        bigint                   not null default 1,
    risk_score         integer                  not null default 0,
    evidence_json      jsonb,
    first_seen_at      timestamp with time zone not null,
    last_seen_at       timestamp with time zone not null
);

create table blocked_domains
(
    id          bigserial primary key,
    domain_name varchar(255)             not null,
    reason      text,
    status      smallint                 not null default 1,
    created_at  timestamp with time zone not null default now(),
    updated_at  timestamp with time zone not null default now()
);

create table risk_devices
(
    id             bigserial primary key,
    device_risk_id varchar(128)             not null,
    reason         text,
    status         smallint                 not null default 1,
    created_at     timestamp with time zone not null default now(),
    updated_at     timestamp with time zone not null default now()
);

alter table repost_licenses
    add constraint uq_repost_licenses_code unique (code);

alter table affiliate_tokens
    add constraint uq_affiliate_tokens_hash unique (token_hash);

alter table redirect_click_events
    add constraint uq_redirect_click_events_key unique (event_key);

alter table blocked_domains
    add constraint uq_blocked_domains_domain unique (domain_name);

alter table risk_devices
    add constraint uq_risk_devices_device unique (device_risk_id);

alter table repost_licenses
    add constraint fk_repost_licenses_post foreign key (article_id) references posts (id);

alter table repost_licenses
    add constraint fk_repost_licenses_user foreign key (viewer_user_id) references users (id);

alter table affiliate_tokens
    add constraint fk_affiliate_tokens_post foreign key (article_id) references posts (id);

alter table affiliate_tokens
    add constraint fk_affiliate_tokens_affiliate_link foreign key (affiliate_link_id) references affiliate_links (id);

alter table affiliate_tokens
    add constraint fk_affiliate_tokens_user foreign key (viewer_user_id) references users (id);

alter table affiliate_tokens
    add constraint fk_affiliate_tokens_license foreign key (repost_license_id) references repost_licenses (id);

alter table redirect_click_events
    add constraint fk_redirect_click_events_token foreign key (affiliate_token_id) references affiliate_tokens (id);

alter table redirect_click_events
    add constraint fk_redirect_click_events_license foreign key (repost_license_id) references repost_licenses (id);

alter table suspicious_reposts
    add constraint fk_suspicious_reposts_token foreign key (affiliate_token_id) references affiliate_tokens (id);

alter table suspicious_reposts
    add constraint fk_suspicious_reposts_license foreign key (repost_license_id) references repost_licenses (id);

create index idx_repost_licenses_article on repost_licenses (article_id);
create index idx_repost_licenses_user on repost_licenses (viewer_user_id);
create index idx_affiliate_tokens_short_display on affiliate_tokens (short_display);
create index idx_redirect_click_events_token_time on redirect_click_events (affiliate_token_id, clicked_at);
create index idx_redirect_click_events_sync on redirect_click_events (synced_to_primary, clicked_at);
create index idx_suspicious_reposts_token_domain on suspicious_reposts (affiliate_token_id, suspicious_domain);

comment on table affiliate_links is '商业或 affiliate 原始目标链接';
comment on table repost_licenses is '转载授权记录';
comment on table affiliate_tokens is '有状态短链 token 表，只存 token hash';
comment on table redirect_click_events is 'go 短链点击证据链';
comment on table suspicious_reposts is '可疑转载聚合记录';
comment on table blocked_domains is '封禁域名';
comment on table risk_devices is '风险设备封禁';

-- rollback drop table if exists risk_devices;
-- rollback drop table if exists blocked_domains;
-- rollback drop table if exists suspicious_reposts;
-- rollback drop table if exists redirect_click_events;
-- rollback drop table if exists affiliate_tokens;
-- rollback drop table if exists repost_licenses;
-- rollback drop table if exists affiliate_links;
