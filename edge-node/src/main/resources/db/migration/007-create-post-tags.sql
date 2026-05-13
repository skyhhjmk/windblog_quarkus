-- liquibase formatted sql

-- changeset biliwind:007-create-post-tags
create table post_tags
(
    post_id bigint not null,
    tag_id  bigint not null,
    primary key (post_id, tag_id)
);

comment on table post_tags is '文章与标签关联表';

comment on column post_tags.post_id is '文章ID';
comment on column post_tags.tag_id is '标签ID';

-- rollback drop table post_tags;
