-- liquibase formatted sql

-- changeset biliwind:034-add-post-category
-- 添加文章分类关联字段
alter table posts
    add column category_id bigint;

comment on column posts.category_id is '文章所属分类ID';

-- 添加外键约束
alter table posts
    add constraint fk_post_category
        foreign key (category_id) references categories (id);

-- 添加索引
create index idx_posts_category_id on posts (category_id);

-- rollback alter table posts drop column category_id;
