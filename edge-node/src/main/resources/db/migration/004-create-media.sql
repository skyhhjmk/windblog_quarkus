-- liquibase formatted sql

-- changeset biliwind:004-create-media
create table media
(
    id          bigserial primary key,
    storage_key varchar(255) not null,
    url         text         not null,
    media_type  smallint     not null,
    mime_type   varchar(100),
    size        bigint,
    width       integer,
    height      integer,
    alt         jsonb,
    metadata    jsonb,
    created_at  timestamptz  not null default now(),
    deleted_at  timestamptz
);

comment on table media is '媒体资源表';

comment on column media.id is '媒体ID';
comment on column media.storage_key is '存储路径唯一标识';
comment on column media.url is '访问URL';
comment on column media.media_type is '类型：0=图片 1=视频 2=文件';
comment on column media.mime_type is 'MIME类型';
comment on column media.size is '文件大小（字节）';
comment on column media.width is '宽度';
comment on column media.height is '高度';
comment on column media.alt is '多语言alt文本 JSONB';
comment on column media.metadata is '扩展元数据 JSONB';
comment on column media.created_at is '创建时间';
comment on column media.deleted_at is '软删除时间';

-- rollback drop table media;
