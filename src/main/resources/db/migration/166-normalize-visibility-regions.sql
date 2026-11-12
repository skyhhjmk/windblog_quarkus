-- liquibase formatted sql
-- changeset biliwind:166-normalize-visibility-regions
-- Reader visibility is strict lowercase; null, empty arrays and global all mean "visible everywhere".
WITH normalized_posts AS (
    SELECT id,
           CASE
               WHEN jsonb_array_length(visibility_regions) = 0 THEN NULL
               ELSE (
                   SELECT jsonb_agg(DISTINCT normalized ORDER BY normalized)
                   FROM (
                       SELECT CASE lower(btrim(v.value))
                                  WHEN 'china' THEN 'cn'
                                  ELSE lower(btrim(v.value))
                              END AS normalized
                       FROM jsonb_array_elements_text(visibility_regions) AS v(value)
                       WHERE btrim(v.value) <> ''
                   ) codes
               )
           END AS regions
    FROM posts
    WHERE visibility_regions IS NOT NULL
)
UPDATE posts p
SET visibility_regions = CASE
        WHEN n.regions IS NULL THEN NULL
        WHEN n.regions @> '["global"]'::jsonb THEN NULL
        ELSE n.regions
    END
FROM normalized_posts n
WHERE p.id = n.id;

WITH normalized_media AS (
    SELECT id,
           CASE
               WHEN jsonb_array_length(visibility_regions) = 0 THEN NULL
               ELSE (
                   SELECT jsonb_agg(DISTINCT normalized ORDER BY normalized)
                   FROM (
                       SELECT CASE lower(btrim(v.value))
                                  WHEN 'china' THEN 'cn'
                                  ELSE lower(btrim(v.value))
                              END AS normalized
                       FROM jsonb_array_elements_text(visibility_regions) AS v(value)
                       WHERE btrim(v.value) <> ''
                   ) codes
               )
           END AS regions
    FROM media
    WHERE visibility_regions IS NOT NULL
)
UPDATE media m
SET visibility_regions = CASE
        WHEN n.regions IS NULL THEN NULL
        WHEN n.regions @> '["global"]'::jsonb THEN NULL
        ELSE n.regions
    END
FROM normalized_media n
WHERE m.id = n.id;
