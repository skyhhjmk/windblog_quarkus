-- changeset biliwind:082-fix-empty-visibility-regions
-- 把 visibility_regions 为 '[]' 的记录更新为 NULL，修复这些文章/媒体在区域过滤中被漏掉的问题。
UPDATE posts SET visibility_regions = NULL WHERE visibility_regions = '[]'::jsonb;
UPDATE media SET visibility_regions = NULL WHERE visibility_regions = '[]'::jsonb;
