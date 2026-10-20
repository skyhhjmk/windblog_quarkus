-- liquibase formatted sql

-- changeset windblog:147-fail-stuck-ai-comment-audits
WITH latest_failed AS (
    SELECT DISTINCT ON (aggregate_id)
           aggregate_id,
           last_error,
           created_at,
           id
    FROM outbox_events
    WHERE event_type = 'AI_AUDIT'
      AND aggregate_type = 'COMMENT'
      AND status = 'FAILED'
    ORDER BY aggregate_id, created_at DESC, id DESC
)
UPDATE comments c
SET is_reviewing = FALSE,
    audit_status = 4,
    ai_review_data = COALESCE(c.ai_review_data, '{}'::jsonb) || jsonb_build_object(
        'reviewRequired', TRUE,
        'failed', TRUE,
        'reason', 'AI 审核任务失败，可重新发起审核',
        'error', COALESCE(f.last_error, 'AI 审核任务失败'),
        'auditTime', (EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::bigint
    ),
    updated_at = CURRENT_TIMESTAMP
FROM latest_failed f
WHERE c.id::text = f.aggregate_id
  AND (c.is_reviewing = TRUE OR c.audit_status = 1)
  AND NOT EXISTS (
      SELECT 1
      FROM outbox_events newer
      WHERE newer.event_type = 'AI_AUDIT'
        AND newer.aggregate_type = 'COMMENT'
        AND newer.aggregate_id = f.aggregate_id
        AND newer.created_at > f.created_at
        AND newer.status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')
  );
