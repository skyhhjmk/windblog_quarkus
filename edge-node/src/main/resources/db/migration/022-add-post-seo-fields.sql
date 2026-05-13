-- liquibase formatted sql

-- changeset biliwind:022-add-post-seo-fields
alter table posts
    add column seo_title varchar(255);
alter table posts
    add column seo_keywords varchar(255);
alter table posts
    add column seo_description text;

comment on column posts.seo_title is 'SEO 标题';
comment on column posts.seo_keywords is 'SEO 关键词';
comment on column posts.seo_description is 'SEO 描述';

-- rollback alter table posts drop column seo_title, drop column seo_keywords, drop column seo_description;
