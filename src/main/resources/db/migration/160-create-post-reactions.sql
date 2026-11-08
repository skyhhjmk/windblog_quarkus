-- liquibase formatted sql

-- changeset biliwind:160-create-post-reactions
create table if not exists post_reactions
(
    id               bigserial primary key,
    post_slug        varchar(160) not null unique,
    like_count       bigint       not null default 0,
    heart_count      bigint       not null default 0,
    rocket_count     bigint       not null default 0,
    celebrate_count  bigint       not null default 0,
    insightful_count bigint       not null default 0,
    created_at       timestamptz  not null default now(),
    updated_at       timestamptz  not null default now()
);

create index if not exists idx_post_reactions_slug on post_reactions (post_slug);

comment on table post_reactions is '文章表情互动与点赞汇总表';
comment on column post_reactions.post_slug is '文章唯一标识';
comment on column post_reactions.like_count is '点赞数 (like)';
comment on column post_reactions.heart_count is '喜欢/感谢数 (heart)';
comment on column post_reactions.rocket_count is '强推数 (rocket)';
comment on column post_reactions.celebrate_count is '庆祝数 (celebrate)';
comment on column post_reactions.insightful_count is '启发数 (insightful)';

-- rollback drop table if exists post_reactions;
