--liquibase formatted sql

--changeset windblog:115-allow-codex-category-selection
ALTER TABLE codex_creator_draft_assignments
    ALTER COLUMN category_id DROP NOT NULL;

--rollback ALTER TABLE codex_creator_draft_assignments
--rollback     ALTER COLUMN category_id SET NOT NULL;
