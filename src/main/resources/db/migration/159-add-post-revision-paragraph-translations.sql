-- liquibase formatted sql

-- changeset biliwind:159-add-post-revision-paragraph-translations
alter table post_revisions
    add column if not exists paragraph_translations jsonb;

comment on column post_revisions.paragraph_translations is '各语言段落级翻译与源Hash映射 (JSONB)';

-- rollback alter table post_revisions drop column if exists paragraph_translations;
