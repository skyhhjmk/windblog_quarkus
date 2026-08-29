# Codex Creator 集成说明

Codex Creator 作为独立仓库 `codex-creator` 挂载在本仓库的 `codex-creator/` 子模块路径中。WindBlog 只通过 `com.biliwind.blog.service.ai.CodexCreatorHttpClient` 和 `CodexCreatorEventPublisher` 访问它，不依赖 Codex Creator 的 Java 类型或数据库表。

## 内部契约

- 运行时：`POST /api/v1/runtime/infer`，字段为 `operation`、`profileId`、`input`、`idempotencyKey`、`traceId`、`promptVersion`。
- 事件：`POST /api/internal/integrations/windblog/events`，Outbox 只发布 `comment.created`、`link.application.created`、`link.monitor.completed`、`post.revision.updated`、`post.published`。
- 签名：`X-Codex-Client-Id`、`X-Codex-Timestamp`、`X-Codex-Nonce`、`X-Codex-Body-SHA256`、`X-Codex-Signature`。签名 canonical string 为 `timestamp + "\\n" + nonce + "\\n" + bodySha256 + "\\n" + clientId`，HMAC-SHA256 输出 hex。时间窗和 nonce 防重放，不能复用管理员 JWT。
- 来源：`post_ai_metadata` 保存任务、模型、思考级别、生成方式、来源和自动发布状态；AI 专区依赖这些系统级元数据，不依赖特殊分类。

## 安全与部署

Compose/Kubernetes 默认只在内部网络暴露 `codex-creator:8090`，不配置公网端口。OpenAPI/Swagger、管理 API 和 `/mcp` 均为私有面；如确需访问，运维人员必须手工建立带源地址 allowlist、VPN/认证、有效期和撤销记录的 APISIX 路由。

在已有 PostgreSQL 卷上切换 `pgvector/pg18` 前，按仓库内的 [Codex Creator 部署手册](../codex-creator/docs/runbook.md) 完成备份和回滚演练。新卷初始化脚本以及 Compose/Kubernetes 的幂等启动初始化都会确保 `codex_creator` 存在并在该数据库执行 `CREATE EXTENSION vector`；不会修改 WindBlog 既有表，也不会删除数据卷。

Kubernetes 部署要求预先创建名为 `codex-creator-secrets` 的 Secret，且必须包含
`admin-token`、`internal-shared-secret`、`mcp-bearer-token` 三个键。启用
Codex app-server 时，再配置 `openai-api-key` 或 `codex-access-token` 其中一个。
可复制
`k8s/secrets/codex-creator-secrets.env.example` 为未跟踪的 `.env` 文件，填入独立随机值后按文件中的命令创建 Secret；部署清单会把缺失 Secret 视为配置错误，不再静默启动。WindBlog 事件集成和 Codex app-server 在该清单中已启用，因此还必须确认镜像内的 `codex` 已完成认证和模型可用性。

## 验收边界

Java 编译、JVM 测试、容器构建和健康检查只能证明代码及基础设施具备能力。真实上线还要在目标环境验证 Codex 登录/额度/模型、真实 app-server 握手、MCP 工具、签名事件、SSRF/爬取策略、备份恢复、APISIX 路由和评论/友链/文章完整业务链路。
