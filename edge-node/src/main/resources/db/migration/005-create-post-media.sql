-- liquibase formatted sql

-- changeset biliwind:005-create-post-media
create table post_media
(
    post_id    bigint      not null,
    media_id   bigint      not null,
    usage_type smallint    not null,
    position   integer,
    created_at timestamptz not null default now(),
    primary key (post_id, media_id)
);

comment on table post_media is '文章与媒体关联表';

comment on column post_media.post_id is '文章ID';
comment on column post_media.media_id is '媒体ID';
comment on column post_media.usage_type is '用途：0=正文 1=特色图 2=画廊 3=OG图';
comment on column post_media.position is '排序位置';
comment on column post_media.created_at is '创建时间';

-- rollback drop table post_media;
