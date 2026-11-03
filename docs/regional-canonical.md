# 多区域 canonical

每个启用的区域域名各自对外发布 canonical：`.cn` 中国站指向 `.cn` 域名，`.com` 国际站指向 `.com` 域名。

标准站点地址按以下顺序解析：

1. 当前请求区域（Host/语言规则匹配，见 `RegionRuleService`）对应的已启用域名规则；同一区域同时配置 www 与非 www 规则时优先 www，统一使用 HTTPS。
2. 多区域启用时，`site_info.site_url_regions.<region>` 或 `site_info.site_url`。
3. 部署配置 `windblog.site.public-url`。

没有命中任何区域域名规则时使用部署配置，因此后台任务、邮件发送等没有请求上下文的调用仍指向 `WINDBLOG_SITE_PUBLIC_URL`。

canonical 的默认语言跟随当前区域（`site_language.regions.<region>`）：该区域默认语言不带前缀，其它语言带 `/zh-cn` 等前缀。普通页面保留页面路径，忽略查询参数；文章页沿用已有的 `/post/{slug}` 标准路径。AMP canonical 指向普通文章页，PJAX 导航同步更新 canonical。sitemap、RSS、robots.txt、`.well-known` 文档以及邮件链接使用同一套区域解析。该功能无需数据库迁移。
