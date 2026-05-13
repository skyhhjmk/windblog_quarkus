-- liquibase formatted sql

-- changeset biliwind:009-create-comments
create table comments
(
    id         bigserial primary key,
    post_id    bigint      not null,
    parent_id  bigint,
    user_id    bigint,
    content    text        not null,
    status     smallint    not null default 0,
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);

comment on table comments is '评论表';

comment on column comments.id is '评论ID';
comment on column comments.post_id is '所属文章ID';
comment on column comments.parent_id is '父评论ID';
comment on column comments.user_id is '评论用户ID';
comment on column comments.content is '评论内容';
comment on column comments.status is '状态：0=待审核 1=通过 2=垃圾';
comment on column comments.created_at is '创建时间';
comment on column comments.deleted_at is '软删除时间';

-- rollback drop table comments;
