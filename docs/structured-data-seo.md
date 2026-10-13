# 结构化数据 SEO

公开 HTML 和 PJAX 片段共用 `StructuredDataService` 生成的 JSON-LD：

- 首页：`WebSite` 与发布者 `Organization`；分页首页使用 `CollectionPage`。
- 文章与可用的 AMP 文章：`BlogPosting`、发布者、`BreadcrumbList`。
- 分类和标签的列表、详情页：`CollectionPage` 与 `BreadcrumbList`；分类详情保留现有分类路径。

JSON-LD 使用 Jackson 序列化，并将 HTML 敏感字符转为 JSON Unicode 转义后才嵌入脚本标签。缺失日期不输出空字符串，序列化失败记录日志并省略标记。PJAX 进入或离开这些页面时替换头部结构化数据，避免留下上一页的文章信息。

## 作者与图片

后台「基础设置」中的「站点作者」须与文章显示的作者姓名一致，才能使用「作者公开介绍页」和「作者身份链接」生成 `author.url`、`author.sameAs`。填写真实、公开可访问的作者主页和社交账号；留空时不生成链接。访客或 AI 作者不会继承站点作者的身份链接，未知作者不生成虚构的 Person。多作者站点仍须为其他作者建立真实公开资料后再接入身份链接。

文章 `image` 从已净化的公开正文或预览中提取，去重后最多输出三张，支持普通图片与 AMP 图片。正文净化保留相对图片与链接地址，避免改写成 localhost 后被媒体域名策略过滤。元数据中的相对地址解析为绝对 HTTP(S) URL；空地址、凭据 URL、data/javascript URL 被忽略。没有公开图片的文章不以站点 logo 冒充文章图片。发布者 logo 也解析为绝对地址；旧默认值 `/logo.png` 没有对应的内置文件时省略该声明，可在后台配置真实可访问的 Logo 链接。普通文章的 Open Graph/Twitter 图片优先使用相同的文章图片。

分类旧路径没有解析出当前页时，结构化面包屑自动追加当前分类，保留能解析出的祖先节点，确保不会只剩下首页。

优先使用清晰、与文章相关、无需登录即可访问的图片。这里不会生成裁剪图或探测远程图片可达性，图片尺寸、抓取权限及索引状态仍需在实际站点检查。

## 付费文章

价格大于零的文章输出 `isAccessibleForFree: false`，并通过 `hasPart` 指向正文的 `.post-paywall` 类。价格为零的文章输出 `true`。元数据只读取公开快照的预览图片，避免将已授权用户获取的付费原文图片地址写入公开元数据；现有授权与购买逻辑保持不变。

## 验证

```bash
bash mvnw -Dtest=StructuredDataServiceTest,AmpContentRendererTest,PjaxResponseFilterTest test
```

部署后分别检查首页、有图文章、无图文章、付费文章、分类/标签详情、AMP 与 PJAX 导航。使用 Google Rich Results Test 验证文章、面包屑等受支持的类型，使用 Schema Markup Validator 检查通用 Schema.org 数据；`WebSite` 不必在富媒体结果测试中显示为单独的富媒体类型。

在 Search Console 的 URL 检查中验证真实公开域名的抓取结果与图片可达性。有效标记不保证 Google 展示富媒体结果。
