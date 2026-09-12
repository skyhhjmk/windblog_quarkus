-- liquibase formatted sql
-- changeset windblog:121-add-codex-draft-repost-policy

ALTER TABLE codex_creator_draft_assignments
    ADD COLUMN IF NOT EXISTS repost_policy_code VARCHAR(64) DEFAULT 'REQUEST_REQUIRED';

UPDATE codex_creator_draft_assignments assignment
SET repost_policy_code = COALESCE(post.repost_policy_code, 'REQUEST_REQUIRED')
FROM posts post
WHERE assignment.post_id = post.id
  AND (assignment.repost_policy_code IS NULL
       OR assignment.repost_policy_code = 'REQUEST_REQUIRED');

UPDATE codex_creator_draft_assignments
SET repost_policy_code = 'REQUEST_REQUIRED'
WHERE repost_policy_code IS NULL;

ALTER TABLE codex_creator_draft_assignments
    ALTER COLUMN repost_policy_code SET NOT NULL;

-- rollback ALTER TABLE codex_creator_draft_assignments DROP COLUMN repost_policy_code;
