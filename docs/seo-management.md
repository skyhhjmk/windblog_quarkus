# SEO 管理

管理端入口为「AMP / SEO → SEO 管理」。文章编辑器的发布设置中新增搜索标题、描述和关键词；留空时沿用文章标题、摘要、AI 摘要或正文回退。文章 SEO 字段当前沿用后端已有的单值字段，不是多语言字段。

## 多语言 SEO 字段

文章的 `seo_title`、`seo_keywords`、`seo_description` 以 JSONB 按语言保存（`152-add-post-seo-i18n` 迁移会把旧单值归入文章主语言）。后台现有单值输入仍可用，写入文章主语言；API 还可通过 `seoTitleTranslations`、`seoKeywordsTranslations`、`seoDescriptionTranslations` 编辑所有语言，响应同时返回单值（主语言）和完整映射。公开页按实际正文语言读取 SEO，缺失时才回退标题/摘要，不会把其他语言的 SEO 文案用于当前页面。

一键 AI 翻译会同时翻译已填写的 SEO 标题、描述和关键词；`seoKeywords` 输出目标语言关键词短语，其他内容字段保持原有 Markdown 结构校验。

## Sitemap 与多语言

`/sitemap.xml` 按 1000 篇文章或分类/标签一组列出分片。`/sitemap-posts.xml`、`/sitemap-categories.xml` 和 `/sitemap-tags.xml` 保留为第一片兼容入口；搜索引擎应提交索引地址，索引会列出全部分片。

仅将已发布、公开、有发布版本、global 区域可见且未设置 noindex 的文章放入文章地图。只发布同时具有对应标题和正文的语言版本，输出 hreflang、双向 alternate 和可用默认语言的 x-default。普通 HTML、AMP 和 PJAX 也输出文章语言关联。文章地图从免费公开正文提取最多三张图片，不读取付费原文图片。

Sitemap、robots 中的 Sitemap 地址及 Canonical 均使用相同的 global 规范域名策略。页面、分类、标签和友链详情都会按语言分别列出 URL，并输出 `xhtml:link` 语言关联；文章地图的 alternates 直接复用已加载的发布版本，避免逐条重复查询。友链详情使用独立的 `/sitemap-links-{page}.xml` 分片，只包含已通过且公开可见的友链。站点地图没有主动通知或保证收录的含义，应在 Search Console 中提交。sitemap、robots 与 RSS 响应带有 `Cache-Control: public, max-age=3600`；RSS 同时声明 `Vary: Cookie`，因为订阅语言跟随语言 Cookie。

## 标题、描述与分页

首页标题使用站点标题与副标题（副标题仅在同语言为区域默认语言时追加），分页首页追加页码；`og:title` 与 `<title>` 保持一致。分类/标签列表与详情页使用当前语言生成标题，并优先使用分类/标签自身描述作为 meta description；分页页标题追加页码。

分类、标签的 `?page=N` 页面输出自引用 canonical（包含页码）；带 `q=` 的筛选页 canonical 指回未筛选的列表页。`/page/1` 永久重定向到语言首页，`/page/0` 同样处理，超出总页数的页码返回 404，不再以 200 渲染空列表。

## 语言回退页

文章只在标题与正文都存在的语言下输出 hreflang alternates（与 sitemap 一致），x-default 优先使用区域默认语言。访问不存在翻译的语言 URL 时仍会回退渲染正文，但 canonical、AMP canonical 与结构化数据 URL 指向实际正文语言，避免回退页作为重复内容参与索引。

## 收录与跳转

系统设置 `seo` 控制公开页面的全站 index/follow，并提供 Google 验证码字段。文章可单独设置 noindex/nofollow；单篇禁止标记优先于全站允许设置。HTML meta 和响应 `X-Robots-Tag` 同步输出。搜索页允许抓取并返回 noindex，使搜索引擎能读取禁止收录规则。后台、API 和 Quarkus 管理端点（`/q/`）仍通过 robots 禁止抓取，`/q/` 响应同时带 `X-Robots-Tag: noindex`。

重定向支持站内路径的 301、302、307 和 308；拒绝系统路径、外站目标、自指和循环，串联规则直接指向最终目标。仅处理 GET/HEAD，不转发旧 URL 的查询参数。修改文章 slug 时保留普通页、AMP 和已支持语言页的旧 URL 301。

文章编辑器可直接编辑 slug，并按标题自动生成：只保留小写字母、数字和连字符，中文按拼音音译（由后端 ICU 音译实现，不依赖 AI）；手动修改后不再随标题自动变化。AI 翻译返回由译文标题生成的 slug，应用译文时会先询问是否替换现有 slug，确认后保存会沿用上面的旧链接 301。

404 监控记录路径、最近来源、次数和处理状态；来源删除账号、查询和片段，不记录 IP。保留 90 天，定时压缩至最近 10000 个路径。新请求再次触发 404 时重新打开处理状态。

## 内容检查

内容和图片检查针对已发布版本，检查标题、描述、正文长度、标题层级、目标关键词和图片 ALT。分数是编辑建议，不是排名预测；装饰图片可以人工确认保留空 ALT。图片地图不探测图片响应或实际索引状态。

内链分析分页返回文章，扫描所有已发布公开文章的各语言正文，提供入链引用数、零正文入链提示及失效目标。零正文入链不等于搜索引擎无法发现文章，首页、分类和地图仍可提供入口。相关文章按主分类选取最近五篇公开 global 文章。

AI 推荐使用已启用且允许 `assistant` 的现有 AI 提供商，只输出标题、描述和关键词建议。编辑确认应用到编辑器后仍需保存；不会自动发布或覆盖原文。未配置 AI 提供商时显示明确错误。

## Google Search Console

当前按用户要求作为保留功能，未连接真实账户或查询真实统计。

1. 在 Google Cloud 项目启用 Search Console API，配置 OAuth 客户端。
2. 通过 Google OAuth 授权取得包含 `https://www.googleapis.com/auth/webmasters.readonly` 的 refresh token；授权用户必须能访问目标 Search Console 属性。
3. 在服务器环境配置 `GSC_CLIENT_ID`、`GSC_CLIENT_SECRET`、`GSC_REFRESH_TOKEN`、`GSC_SITE_URL` 后重启 WindBlog。Compose 已透传这些变量。
4. `GSC_SITE_URL` 必须与已验证属性完全一致，例如 `sc-domain:example.com` 或 `https://example.com/`。
5. URL 前缀属性可在系统 SEO 设置填写 Google 提供的 HTML 验证码；域名属性仍需 DNS 验证。验证码不会代替 OAuth 授权。

管理端可按搜索词、页面、日期、国家或设备查询点击、曝光、CTR 与平均排名。后端支持 `offset` 分页，每次最多 100 行；Google API 可能只返回主要数据，不能视为完整搜索词导出。默认查询最终数据。凭据只在服务器环境，管理接口只返回是否已配置及属性名。

API 参考：

- [Search Analytics query](https://developers.google.com/webmaster-tools/v1/searchanalytics/query)
- [Google OAuth refresh token](https://developers.google.com/identity/protocols/oauth2/web-server)

## 可视化内部链接图

在「系统设置 → SEO」开启「启用内部链接图」（`seo.internal_link_graph_enabled`）。默认关闭，关闭时前后台图形入口隐藏，图数据接口返回 404，设置热更新后立即生效。

- 前台文章页：展示当前文章公开预览中直接引用的公开文章，箭头表示引用方向，节点可跳转；最多 80 个节点，不返回二层或更深关系。密码文章、未发布文章、不可见区域文章和付费隐藏部分中的引用不会通过公开图泄露。
- 后台「AMP / SEO → SEO 管理 → 内链分析」提供图形入口，文章检查页也可直接进入。选择文章 ID 和 1–8 层递归深度，支持缩放、拖动、点击节点重新分析。
- 后台按已发布公开文章的完整正文递归分析，因此可分析付费正文中的引用；须有文章读取权限。默认最多 150 个节点，接口允许最多 300 个；达到节点、引用数量、正文长度或时间预算时显示截断提示。
- 图形分析仅沿正文出链展开，不将分类推荐、评论或导航链接当作正文引用。循环关系会保留为边，但不会反复遍历；旧 slug 按现有跳转规则解析，站外链接不进入图。

公开接口固定只有一层，不接受递归深度控制；后台递归接口独立位于受保护的管理 API 下。公开图返回 `Cache-Control: no-store`，PJAX 切换会取消旧请求并加载新文章图。
