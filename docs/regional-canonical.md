# 多区域 canonical

存在已启用的非 `global` 区域规则时，各区域的 HTML canonical 链接统一指向 global 区域的对应页面，global 页面也使用同一个标准地址。

标准站点地址按以下顺序解析：

1. 已启用的 global 域名规则，按规则优先级选择，使用 HTTPS。
2. 全局 `site_info.site_url`。
3. 部署配置 `windblog.site.public-url`。

没有启用的非 global 区域规则时，继续使用部署配置中的站点地址。

普通页面保留页面路径，忽略查询参数；文章页沿用已有的 `/post/{slug}` 标准路径。AMP canonical 指向普通文章页，PJAX 导航同步更新 canonical。该功能无需数据库迁移。
