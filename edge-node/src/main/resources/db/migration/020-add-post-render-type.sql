-- liquibase formatted sql

-- changeset biliwind:020-add-post-render-type
alter table posts
    add column render_type smallint not null default 0;

comment on column posts.render_type is '渲染类型：0=markdown 1=html 2=vditor 3=v_builder 4=gutenberg';

-- rollback
-- alter table posts drop column render_type;
