-- liquibase formatted sql
-- changeset windblog:122-add-editable-legal-content

INSERT INTO system_settings (config_key, config_value, config_type, group_name, ui_schema, description)
VALUES
('legal_terms', jsonb_build_object('html', $$<h1>用户协议</h1><p>欢迎使用 WindBlog。注册和使用本站服务即表示您同意遵守本协议以及适用的法律法规。</p><h2>账号</h2><p>您应提供真实、准确的注册信息并妥善保管账号凭据。不得冒用他人身份或利用本站从事违法活动。</p><h2>内容</h2><p>您对自行发布的内容负责，不得发布侵犯他人权利、恶意程序或违反法律法规的内容。我们可能依法处理违规内容。</p><h2>服务变更</h2><p>在必要时我们会调整功能或维护服务，并尽量提前告知重大变化。</p>$$), 'string', 'legal', '{"type":"object","fields":[{"key":"html","label":"用户协议内容（HTML）","widget":"textarea","required":true}]}', '公共用户协议内容'),
('legal_privacy', jsonb_build_object('html', $$<h1>隐私政策</h1><p>我们只在提供账号、评论、订阅和安全功能所必需的范围内处理个人信息。</p><h2>收集的信息</h2><p>注册时会保存用户名、邮箱和密码哈希。密码重置令牌只保存哈希值，并在使用或过期后失效。</p><h2>邮件</h2><p>邮箱验证、密码重置和您主动订阅的通知会通过站点配置的邮件服务发送。我们不会在页面或日志中展示密码和令牌。</p><h2>您的权利</h2><p>如需更正或删除账号信息，请通过站点公布的联系方式联系管理员。</p>$$), 'string', 'legal', '{"type":"object","fields":[{"key":"html","label":"隐私政策内容（HTML）","widget":"textarea","required":true}]}', '公共隐私政策内容')
ON CONFLICT (config_key) DO NOTHING;
