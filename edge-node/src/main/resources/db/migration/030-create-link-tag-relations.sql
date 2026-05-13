-- liquibase formatted sql

-- changeset biliwind:030-create-link-tag-relations
create table link_tag_relations
(
    link_id bigint not null,
    tag_id  bigint not null,
    primary key (link_id, tag_id)
);

comment on table link_tag_relations is '链接与标签关联表';

comment on column link_tag_relations.link_id is '链接ID';
comment on column link_tag_relations.tag_id is '标签ID';

-- rollback drop table link_tag_relations;
