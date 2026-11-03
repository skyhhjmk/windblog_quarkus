-- liquibase formatted sql

-- changeset biliwind:155-add-ai-provider-concurrency-limit
alter table ai_provider_configs
    add column concurrency_limit integer;

comment on column ai_provider_configs.concurrency_limit is '最大并发数；NULL 或 0 表示不限制';

-- rollback alter table ai_provider_configs drop column concurrency_limit;
