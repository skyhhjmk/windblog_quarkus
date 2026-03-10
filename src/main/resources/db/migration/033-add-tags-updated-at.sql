-- liquibase formatted sql

-- changeset biliwind:033-add-tags-updated-at
ALTER TABLE tags
    ADD updated_at TIMESTAMPTZ DEFAULT now();

-- changeset biliwind:033-add-tags-updated-at-2
ALTER TABLE tags
    ALTER COLUMN updated_at SET NOT NULL;

comment on column tags.updated_at is '更新时间';

-- rollback ALTER TABLE tags DROP COLUMN updated_at;
