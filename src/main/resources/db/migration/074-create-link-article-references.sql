-- liquibase formatted sql

-- changeset biliwind:074-create-link-article-references
create table link_article_references
(
    id              bigserial primary key,
    link_id         bigint                   not null,
    post_id         bigint                   not null,
    anchor_text     text,
    normalized_url  text                     not null,
    reference_count integer                  not null default 1,
    created_at      timestamp with time zone not null default now(),
    updated_at      timestamp with time zone not null default now()
);

alter table link_article_references
    add constraint fk_link_article_references_link foreign key (link_id) references links (id) on delete cascade;

alter table link_article_references
    add constraint fk_link_article_references_post foreign key (post_id) references posts (id) on delete cascade;

alter table link_article_references
    add constraint uq_link_article_references_link_post unique (link_id, post_id);

create index idx_link_article_references_link
    on link_article_references (link_id);

create index idx_link_article_references_post
    on link_article_references (post_id);

comment on table link_article_references is '文章 Markdown 外链引用关系';
comment on column link_article_references.reference_count is '同一文章中引用同一链接的次数';

-- rollback drop table if exists link_article_references;
