-- liquibase formatted sql

-- changeset biliwind:073-add-link-public-token
alter table links
    add column public_token varchar(64);

update links
set public_token = substr(md5(random()::text || clock_timestamp()::text || id::text), 1, 32)
where public_token is null;

create unique index uq_links_public_token
    on links (public_token)
    where public_token is not null;

comment on column links.public_token is '前台跳转使用的随机公开 token，避免暴露自增 ID';

-- rollback drop index if exists uq_links_public_token;
-- rollback alter table links drop column if exists public_token;
