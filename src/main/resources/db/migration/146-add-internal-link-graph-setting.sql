-- liquibase formatted sql
-- changeset windblog:146-add-internal-link-graph-setting
UPDATE system_settings
SET config_value = jsonb_build_object('internal_link_graph_enabled', false) || config_value,
    ui_schema = jsonb_set(ui_schema, '{fields}', COALESCE(ui_schema->'fields', '[]'::jsonb) ||
        '[{"key":"internal_link_graph_enabled","widget":"switch","label":"启用内部链接图","hint":"前台显示当前文章的直接引用关系；后台支持递归分析。"}]'::jsonb),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'seo'
  AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(ui_schema->'fields', '[]'::jsonb)) field
                  WHERE field->>'key' = 'internal_link_graph_enabled');
