-- liquibase formatted sql
-- changeset windblog:124-add-privacy-analytics-opt-out

UPDATE system_settings
SET config_value = config_value || '{"showAnalyticsOptOut": false}'::jsonb
WHERE config_key = 'legal_privacy'
  AND NOT jsonb_exists(config_value, 'showAnalyticsOptOut');

UPDATE system_settings
SET ui_schema = jsonb_set(
        ui_schema,
        '{fields}',
        COALESCE(ui_schema->'fields', '[]'::jsonb) || '[{"key":"showAnalyticsOptOut","label":"显示统计退出表单","widget":"switch","hint":"在首页隐私政策页面显示选择退出表单，访客可让当前浏览器停止发送 SeeRay Lens 统计数据。"}]'::jsonb,
        true
    )
WHERE config_key = 'legal_privacy'
  AND NOT EXISTS (
      SELECT 1
      FROM jsonb_array_elements(COALESCE(ui_schema->'fields', '[]'::jsonb)) AS field
      WHERE field->>'key' = 'showAnalyticsOptOut'
  );
