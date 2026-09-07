# Codex Creator 集成说明

Codex Creator 作为独立仓库 `codex-creator` 挂载在本仓库的 `codex-creator/` 子模块路径中。WindBlog 只通过 `com.biliwind.blog.service.ai.CodexCreatorHttpClient` 和 `CodexCreatorEventPublisher` 访问它，不依赖 Codex Creator 的 Java 类型或数据库表。

## 内部契约

- 运行时：`POST /api/v1/runtime/infer`，字段为 `operation`、`profileId`、`input`、`idempotencyKey`、`traceId`、`promptVersion`。
- 事件：`POST /api/internal/integrations/windblog/events`，Outbox 只发布 `comment.created`、`link.application.created`、`link.monitor.completed`、`post.revision.updated`、`post.published`。
- 内容工具：Codex app-server 通过私有 `/mcp` 只获得 `windblog.list_categories`、`windblog.create_category`、`windblog.update_category`、`windblog.list_tags`、`windblog.create_tag`、`windblog.update_tag` 和 `windblog.upload_image`。这些工具再调用 WindBlog 的签名接口 `POST /api/internal/integrations/codex-creator/content`；分类、标签和图片写入均有父服务幂等记录。没有删除、任意 URL、shell、SQL 或文件写入工具。
- 图片：`windblog.upload_image` 只接收 PNG、JPEG、GIF 或 WebP 的 base64 字节；WindBlog 会重新校验 MIME、Magic Number、病毒扫描和大小，并强制保存 `aiUploaded=true`、`uploadSource=CODEX_CREATOR`、`generationMethod=CODEX_APP_SERVER` 元数据。普通管理员上传不会自动带这个标记。

## 文章生成质量门槛

话题文章采用“研究与写作 → 确定性质检 → 带问题重写 → WindBlog 草稿”的流程。生成结果只有在以下检查全部通过后才会进入 WindBlog：

- 本次任务保留网页搜索证据，至少使用并在正文引用两个独立公开来源；
- 中文正文默认不少于 1200 个有效汉字（其他语言默认不少于 900 词），包含至少 3 个二级章节和 5 个实质段落；
- 开头给出明确判断，`editorialThesis` 必须逐字出现在正文中，同时交代反方观点、代价与判断改变条件；
- 拒绝一级标题重复、模板化 AI 套话、整页网页复制，以及缺少结论/范围/读者价值的摘要；
- Markdown 表格必须满足 GFM 表头、分隔行、列数、转义和空行规则；前台使用 Flexmark TablesExtension 渲染，并为窄屏提供横向滚动。
- 每篇草稿必须引用至少两张由本任务生成、经 `windblog.upload_image` 入库的图片；图片须紧邻其解释段落，并包含中文 alt 文本和斜体图注。
- 启用实操验证时，至少一条当前生成尝试的成功命令记录必须在正文中以自然说明和脱敏输出代码块呈现；旧尝试、模型自述或未执行命令不能作为证据。

首次质检失败会自动把旧稿与逐项问题交给 Codex 重写一次。仍不合格则任务失败，不创建博客草稿；质量报告、提示词版本、实际推理强度和来源会进入任务/文章溯源信息。默认参数可通过 `CODEX_CREATOR_ARTICLE_*`、`CODEX_CREATOR_TOPIC_REASONING_EFFORT` 和 `CODEX_CREATOR_PROMPT_VERSION` 调整，生产调整应基于真实文章样本而不是单纯降低门槛。
- 签名：`X-Codex-Client-Id`、`X-Codex-Timestamp`、`X-Codex-Nonce`、`X-Codex-Body-SHA256`、`X-Codex-Signature`。签名 canonical string 为 `timestamp + "\\n" + nonce + "\\n" + bodySha256 + "\\n" + clientId`，HMAC-SHA256 输出 hex。时间窗和 nonce 防重放，不能复用管理员 JWT。
- 来源：`post_ai_metadata` 保存任务、模型、思考级别、生成方式、来源和自动发布状态；AI 专区依赖这些系统级元数据，不依赖特殊分类。

## 安全与部署

Compose/Kubernetes 默认只在内部网络暴露 `codex-creator:8681`，不配置公网端口。OpenAPI/Swagger、管理 API 和 `/mcp` 均为私有面；如确需访问，运维人员必须手工建立带源地址 allowlist、VPN/认证、有效期和撤销记录的 APISIX 路由。

根 Compose 使用 `CODEX_CREATOR_INTERNAL_SHARED_SECRET` 作为宿主机 `.env` 中的唯一 HMAC 来源，并将同一个值分别注入 WindBlog 的 `WINDBLOG_CODEX_CREATOR_SHARED_SECRET` 和 Codex Creator 的 `CODEX_CREATOR_INTERNAL_SHARED_SECRET` 容器变量。不要再在根 `.env` 中维护两个不同名称的值；修改后需要重建两个应用容器。

在已有 PostgreSQL 卷上切换 `pgvector/pg18` 前，按仓库内的 [Codex Creator 部署手册](../codex-creator/docs/runbook.md) 完成备份和回滚演练。新卷初始化脚本以及 Compose/Kubernetes 的幂等启动初始化都会确保 `codex_creator` 存在并在该数据库执行 `CREATE EXTENSION vector`；不会修改 WindBlog 既有表，也不会删除数据卷。

Kubernetes 部署要求预先创建名为 `codex-creator-secrets` 的 Secret，且必须包含
`admin-token`、`internal-shared-secret`、`mcp-bearer-token` 三个键。启用
Codex app-server 时，再配置 `openai-api-key` 或 `codex-access-token` 其中一个。
可复制
`k8s/secrets/codex-creator-secrets.env.example` 为未跟踪的 `.env` 文件，填入独立随机值后按文件中的命令创建 Secret；部署清单会把缺失 Secret 视为配置错误，不再静默启动。WindBlog 事件集成和 Codex app-server 在该清单中已启用，因此还必须确认镜像内的 `codex` 已完成认证和模型可用性。

## 验收边界

Java 编译、JVM 测试、容器构建和健康检查只能证明代码及基础设施具备能力。真实上线还要在目标环境验证 Codex 登录/额度/模型、真实 app-server 握手、MCP 工具、签名事件、SSRF/爬取策略、备份恢复、APISIX 路由和评论/友链/文章完整业务链路。
