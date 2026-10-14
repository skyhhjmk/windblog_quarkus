-- liquibase formatted sql
-- changeset windblog:144-add-google-adsense-settings
INSERT INTO system_settings (config_key, config_value, group_name, ui_schema, description)
VALUES ('google_adsense', '{"enabled": false, "clientId": "ca-pub-4006222603298393", "autoAds": false, "lazyLoad": true, "rootMargin": 300, "collapseUnfilled": true, "blocks": "[]"}', 'advertising', '{"type": "object", "fields": [{"key": "enabled", "label": "启用 Google AdSense", "widget": "switch"}, {"key": "clientId", "label": "发布商 ID", "widget": "input"}, {"key": "autoAds", "label": "启用自动广告（AdSense 控制台配置）", "widget": "switch"}, {"key": "lazyLoad", "label": "广告块懒加载", "widget": "switch"}, {"key": "rootMargin", "label": "进入视口前预加载距离（像素，0–2000）", "widget": "number"}, {"key": "collapseUnfilled", "label": "折叠未填充广告", "widget": "switch"}, {"key": "blocks", "label": "广告块 JSON 数组", "widget": "textarea", "hint": "示例：[{\"slot\":\"1234567890\",\"placement\":\"sidebar\",\"format\":\"auto\",\"responsive\":true,\"minHeight\":250,\"device\":\"all\"}]。位置：content_top、content_bottom、sidebar；device：all、mobile、desktop；固定尺寸使用 format=fixed、width、height；fluid 支持 layout、layoutKey。"}]}', 'Google AdSense 广告、懒加载与广告块设置')
ON CONFLICT (config_key) DO NOTHING;

-- rollback DELETE FROM system_settings WHERE config_key = 'google_adsense';
