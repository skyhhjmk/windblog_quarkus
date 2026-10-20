# 文章引用卡片

在 Flutter 管理端的 Markdown 编辑器工具栏点击“引用文章”，搜索标题或 slug，选择文章即可在光标处插入引用卡片。支持翻页及加载失败后重试；未公开发布的文章不能选中。编辑器内的默认预览和网站正文都会显示卡片，点击标题使用已有的链接处理逻辑。

保存格式沿用自定义 Markdown 区块，内部是真实文章链接：

```markdown
::: article
[相关文章标题](/post/article-slug)
::: /article
```

可以在链接下空一行手动填写介绍。标题在插入时保存，不自动同步目标文章的后续修改；目标文章取消发布、改为私有或改 slug 后，需修改或移除引用。该功能不读取或嵌入目标文章的正文，也不绕过目标文章的访问权限。相对链接跟随当前站点域名，普通 Markdown 导出仍保留可识别的链接。

引用块不作为信息级别加入阅读偏好筛选。样式定义位于 `assets/css/theme.css` 的 `.article-reference-card`，原生预览定义位于 `article_preview_style.dart` 的 `ArticleReferenceCard`。
