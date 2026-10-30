-- liquibase formatted sql

-- changeset biliwind:151-create-repost-referral-events
create table if not exists repost_referral_events
(
    id                bigserial primary key,
    repost_license_id bigint                   not null,
    article_id        bigint,
    license_code      varchar(64)              not null,
    referer_url       text,
    referer_domain    varchar(255),
    referer_category  varchar(40)              not null default 'unknown_referrer',
    ip_hash           varchar(128),
    user_agent_hash   varchar(128),
    source_node_id    varchar(100),
    viewed_at         timestamp with time zone not null default now()
);

alter table repost_referral_events
    add constraint fk_repost_referral_events_license
        foreign key (repost_license_id) references repost_licenses (id);
alter table repost_referral_events
    add constraint fk_repost_referral_events_post
        foreign key (article_id) references posts (id);

create index if not exists idx_repost_referral_events_license_time
    on repost_referral_events (repost_license_id, viewed_at);
create index if not exists idx_repost_referral_events_code
    on repost_referral_events (license_code);

comment on table repost_referral_events is '转载页原文地址 GET 访问追踪事件';

-- rollback drop table if exists repost_referral_events;
