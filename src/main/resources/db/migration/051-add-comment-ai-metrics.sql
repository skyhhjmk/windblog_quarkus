-- liquibase formatted sql

-- changeset biliwind:051-add-comment-ai-metrics
alter table comments
    add column ai_duration_ms bigint;
alter table comments
    add column ai_total_tokens int;

comment on column comments.ai_duration_ms is 'AI 审核耗时（毫秒）';
comment on column comments.ai_total_tokens is 'AI 审核消耗的总 Token 数';
