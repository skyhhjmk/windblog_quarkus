ALTER TABLE storage_class
    ADD COLUMN IF NOT EXISTS content_regions JSONB;

ALTER TABLE storage_class
    ALTER COLUMN service_region DROP NOT NULL;

UPDATE storage_class
SET content_regions = (
    SELECT jsonb_agg(trimmed_region.region_code)
    FROM (
        SELECT trim(region_part) AS region_code
        FROM unnest(string_to_array(lower(service_region), ',')) AS region_part
        WHERE trim(region_part) IN ('global', 'cn', 'us', 'eu', 'jp', 'hk', 'tw')
    ) AS trimmed_region
)
WHERE content_regions IS NULL
  AND service_region IS NOT NULL
  AND regexp_replace(lower(service_region), '\s+', '', 'g')
      ~ '^(global|cn|us|eu|jp|hk|tw)(,(global|cn|us|eu|jp|hk|tw))*$';

UPDATE storage_class
SET service_region = NULL
WHERE content_regions IS NOT NULL
  AND service_region IS NOT NULL
  AND regexp_replace(lower(service_region), '\s+', '', 'g')
      ~ '^(global|cn|us|eu|jp|hk|tw)(,(global|cn|us|eu|jp|hk|tw))*$';

COMMENT ON COLUMN storage_class.content_regions IS '存储类适用的内容区域列表，空值表示不限制';
