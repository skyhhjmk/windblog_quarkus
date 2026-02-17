-- liquibase formatted sql

-- changeset biliwind:021-add-post-password
alter table posts add column password varchar(100);

comment on column posts.password is '文章访问密码（当 visibility 为 2 时使用）';

-- rollback alter table posts drop column password;
