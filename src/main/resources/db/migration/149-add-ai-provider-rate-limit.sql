-- liquibase formatted sql

-- changeset biliwind:149-add-ai-provider-rate-limit
alter table ai_provider_configs
    add column rate_limit_per_minute integer;

comment on column ai_provider_configs.rate_limit_per_minute is '每分钟最多请求次数；NULL 或 0 表示不限制';

-- rollback alter table ai_provider_configs drop column rate_limit_per_minute;
