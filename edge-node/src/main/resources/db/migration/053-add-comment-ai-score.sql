-- liquibase formatted sql

-- changeset biliwind:053-add-comment-ai-score
alter table comments
    add column ai_score int;

comment on column comments.ai_score is 'AI 审核评分 (0-100)';
