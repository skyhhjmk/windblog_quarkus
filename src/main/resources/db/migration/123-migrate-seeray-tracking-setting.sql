-- liquibase formatted sql
-- changeset biliwind:123-migrate-seeray-tracking-setting
-- Move the legacy raw footer embed into a validated, structured system setting.
INSERT INTO system_settings (config_key, config_value, config_type, group_name, ui_schema, description)
SELECT 'analytics_tracking',
       jsonb_build_object(
           'enabled', true,
           'scriptUrl', (regexp_match(config_value->>'custom_html', 'src="([^"]+/tracker[.]js)"'))[1],
           'siteId', (regexp_match(config_value->>'custom_html', 'data-site-id="([^"]+)"'))[1]),
       'object', '访问分析', '{"type":"object","fields":[]}'::jsonb,
       '由数据库管理的 SeeRay Lens 追踪配置；保存后自动更新页面 CSP。'
FROM system_settings
WHERE config_key = 'site_footer'
  AND config_value->>'custom_html' ~ '<script[^>]+src="[^"]+/tracker[.]js"[^>]+data-site-id="[^"]+"[^>]*></script>'
ON CONFLICT (config_key) DO NOTHING;

UPDATE system_settings
SET config_value = jsonb_set(
        config_value,
        '{custom_html}',
        to_jsonb(regexp_replace(config_value->>'custom_html',
                '<script[^>]+src="[^"]+/tracker[.]js"[^>]+data-site-id="[^"]+"[^>]*></script>', '', 'gi'))),
    updated_at = now()
WHERE config_key = 'site_footer'
  AND EXISTS (SELECT 1 FROM system_settings WHERE config_key = 'analytics_tracking');
