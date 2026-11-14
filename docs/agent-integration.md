# Agent 集成

WindBlog 面向外部 agent 暴露四个面：服务端 A2A 只读发现接口、浏览器内 WebMCP 工具、Flutter 管理端内置 MCP 服务，以及一组供 agent 发现站点能力的文档与协商入口。前三者用途和安全模型不同，不能混用：A2A 完全匿名且只读；WebMCP 使用访问者本人的浏览器会话；Flutter MCP 使用已登录管理员的管理端会话，默认只在本机监听。

| 面 | 入口 | 认证 | 用途 | 主要实现 |
| --- | --- | --- | --- | --- |
| A2A server | `POST /a2a/message:send`、`GET /.well-known/agent-card.json`、`GET /a2a/docs` | 无 | 按关键词发现公开文章，返回最多 5 篇文章与公开 API 文档入口 | `A2aResource`、`AgentCardResource` |
| WebMCP | 全局加载的 `/assets/js/webmcp.js` | 公开读取不带凭据；登录态读取使用 `user_token` Cookie；写请求使用 `X-XSRF-TOKEN` | 在支持 WebMCP 的浏览器中提供 9 个搜索、阅读与翻译工具 | `src/main/resources/META-INF/resources/assets/js/webmcp.js` |
| Flutter admin MCP | 默认 `http://127.0.0.1:58180` 的 `/sse`、`/message`、`/mcp`、`/rpc` | `Authorization: Bearer <accessToken>`，并校验 `Host`/`Origin` | 管理端文章查询、翻译、保存与编辑器应用，共 7 个工具 | `admin-flutter/lib/services/mcp/mcp_server_io.dart` |
| Agent discovery | `/auth.md`、`/.well-known/api-catalog`、`/robots.txt`、`Accept: text/markdown` | 无 | 声明认证方式、API 目录、内容使用信号和 Markdown 表示 | `AuthDiscoveryResource`、`ApiCatalogResource`、`FeedController`、`MarkdownNegotiationFilter` |

## A2A

A2A 面是无状态、只读的公开内容发现服务，不提供账号、任务、流式或回调能力。

### Agent Card

`GET /.well-known/agent-card.json` 返回 `application/json` 的 Agent Card：

- `name`：`GeekCoreLab Public Content Agent`
- `supportedInterfaces[0]`：`url` 为公开基址加 `/a2a`，`protocolBinding` 为 `HTTP+JSON`，`protocolVersion` 为 `1.0`
- `documentationUrl`：公开基址加 `/a2a/docs`
- `capabilities`：`streaming`、`pushNotifications`、`extendedAgentCard` 均为 `false`
- `defaultInputModes` / `defaultOutputModes`：仅 `text/plain`
- `skills`：`public-article-discovery` 与 `public-api-reference`

响应带 `Cache-Control: public, max-age=300`。公开基址由 `PublicUrlService` 解析，会随访问域名（区域域名）变化。

### 发送消息

```http
POST /a2a/message:send
Content-Type: application/a2a+json
Accept: application/a2a+json
A2A-Version: 1.0

{"message":{"messageId":"client-1","role":"ROLE_USER","contextId":"ctx-42","parts":[{"text":"Quarkus"}]}}
```

- `Content-Type` 接受 `application/a2a+json` 和 `application/json`。
- `Accept` 接受 `application/a2a+json` 和 `application/json`；响应按 `Accept` 返回对应媒体类型，其他媒体类型会被协商为 `406`。
- `A2A-Version: 1.0` 必须存在，缺失或其他值返回 `400 VERSION_NOT_SUPPORTED`。
- `message.role` 必须是 `ROLE_USER`，`messageId` 必须为非空字符串，`parts` 必须是非空数组。
- 仅支持文本 part：每个 part 只能有 `text`；带 `data`、`url` 或 `raw` 会返回 `400 CONTENT_TYPE_NOT_SUPPORTED`。所有 part 合并后的文本上限为 4096 字符。
- 可选 `configuration.acceptedOutputModes` 必须是数组，且只要非空就必须包含 `text/plain`。
- 带 `taskId` 的请求返回 `404 TASK_NOT_FOUND`，因为服务不保留任务。

响应示例（`messageId` 每次生成，`contextId` 总是存在）：

```json
{
  "message": {
    "messageId": "6f1c2a76-9d6e-4c31-8f0a-1d2e3f4a5b6c",
    "role": "ROLE_AGENT",
    "contextId": "ctx-42",
    "parts": [
      {
        "text": "Found 2 public articles for \"Quarkus\":\n1. Quarkus Guide - https://www.example.com/post/quarkus-guide\n  A short summary.\n2. Java Basics - https://www.example.com/post/java-basics\n\nSearch public articles: https://www.example.com/search?q=Quarkus\nPublic API reference: https://www.example.com/api/public/openapi\nPublic API documentation: https://www.example.com/api/public/docs"
      }
    ]
  }
}
```

行为要点：

- 回复最多列出 5 篇匹配的公开文章，每篇给出标题、绝对 URL，并在有摘要时给出摘要（超过 200 字符时截断并追加 `...`）。
- 文章列表之后固定附带三个链接：公开搜索页、`/api/public/openapi`、`/api/public/docs`。
- 没有匹配时文本以 `No public articles matched "<query>".` 开头，仍附带上述三个链接。
- 搜索后端异常时降级为只返回三个链接，HTTP 状态仍为 `200`，服务不因检索故障失败。
- 传入 `contextId` 时原样回显；未传入时生成新的 UUID。服务不保留会话历史。
- 搜索使用公开检索服务 `PostSearchService`，按当前请求语言检索，区域参数为 `BlogRegion.GLOBAL`，返回公开文章结果。
- 响应带 `Cache-Control: no-store`。
- 在 edge 节点上，`POST /a2a/message:send` 属于 `EdgeRoutingPolicy` 中 `/a2a` 的回放路径，写请求会被转发到主节点处理。

错误响应统一为：

```json
{
  "error": {
    "code": 400,
    "status": "INVALID_ARGUMENT",
    "message": "A user messageId and non-empty text parts are required.",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "INVALID_ARGUMENT",
        "domain": "a2a-protocol.org"
      }
    ]
  }
}
```

`404` 的 `status` 为 `NOT_FOUND`，其余为 `INVALID_ARGUMENT`；`details[0].reason` 是具体原因（如 `VERSION_NOT_SUPPORTED`、`TASK_NOT_FOUND`、`CONTENT_TYPE_NOT_SUPPORTED`）。

### 限流

`POST /a2a/message:send` 会执行公开搜索工作，因此与 `GET /search` 共用 SEARCH 限流桶：默认每分钟 120 次，按客户端 IP 的 SHA-256 摘要计数，窗口 1 分钟。超限返回 `429`，带 `Retry-After: 60` 和：

```json
{"success":false,"message":"公开阅读请求过于频繁，请稍后重试","reason":"PUBLIC_READ_RATE_LIMIT"}
```

上限由 `security.public-read.search-limit-per-minute`（环境变量 `WINDBLOG_PUBLIC_READ_SEARCH_LIMIT_PER_MINUTE`）控制。`GET /search` 的只读请求和该 A2A POST 共享同一预算，其余写方法不受此过滤器影响。

### 自描述文档

`GET /a2a/docs` 返回 `text/markdown; charset=utf-8`，说明 Agent Card 位置、请求示例、支持的输入输出模式和限制。它是给 agent 读取的简短协议说明，不是 OpenAPI 规范。

## WebMCP

### 加载与启用

`base.html` 在每个公开页面全局加载 `/assets/js/webmcp.js?v=webmcp-20261114`（`defer`）。脚本只在浏览器实现 `navigator.modelContext` 或 `document.modelContext` 且提供 `registerTool` 时注册工具；其他浏览器中脚本静默不执行任何操作，也不会报错。这是实验性 WebMCP API，目前主要出现在支持该能力的 Chromium 系浏览器中，没有 polyfill 或降级方案。

脚本用 `window.windblogWebMcpLoaded` 保证每份文档只初始化一次。PJAX 只替换 `#pjax-container`，不会重新执行页面脚本，因此工具注册不依赖 PJAX 生命周期；`pagehide` 会中止当前工具注册的 `AbortController`，`pageshow`（包括 bfcache 恢复）重新注册。`navigate_site` 通过 `location.assign` 做整页导航，因此之后脚本会按新文档重新加载。

### 工具

共 9 个工具，7 个读取、2 个写入。

| 工具 | 类型 | 输入 | 行为 |
| --- | --- | --- | --- |
| `search_posts` | 读 | `query`、`page` | `GET /search?q=<query>&page=<page>`，解析公开结果页的正文与站内链接 |
| `get_post` | 读 | `slug` | `GET /post/<slug>`，返回标题、正文文本与相关链接；受限内容仍受站点访问规则约束 |
| `get_post_comments` | 读 | `slug`、`page`（从 0 开始） | `GET /api/comments/post/<slug>?page=<page>&size=10` |
| `browse_site` | 读 | `section`（`home`/`categories`/`tags`）、`page` | 读取 `/`、`/category` 或 `/tag` 的公开列表页 |
| `navigate_site` | 读 | `section`（`home`/`categories`/`tags`/`post`）、`slug` | 延迟 100ms 后 `location.assign` 到目标页面 |
| `get_session_status` | 读 | 无 | `GET /user/api/profile`；返回 `authenticated`、`type` 和用户信息 |
| `get_article_for_translation` | 读 | 可选 `slug`、`postId` | 从当前文章页提取标题与正文；或按 slug 读取公开文章 |
| `translate_article` | 写 | `sourceLanguage`、`targetLanguage`，可选 `postId`、`slug`、`title`、`summary`、`contentMarkdown`（`postId`、`slug` 当前不参与翻译请求） | `POST /user/api/posts/translate`，调用站点 AI 翻译服务，不保存文稿 |
| `save_article_translation` | 写 | `postId`、`targetLanguage`、`title`、`contentMarkdown`，可选 `summary` | `PUT /user/api/posts/<postId>/translation`，保存当前用户自己的译本文稿 |

### 认证模型

- 公开读取（`search_posts`、`get_post`、`get_post_comments`、`browse_site`、`get_article_for_translation`）使用 `fetch(..., {credentials: 'omit'})`，不发送任何 Cookie；同时校验最终响应仍为同源，防止外部重定向。
- 登录态读取 `get_session_status` 和两个写入工具使用 `credentials: 'include'`，依赖站点的 `user_token` HTTP Cookie。
- 写请求在非 GET 时读取 `window.getCsrfToken()` 并发送 `X-XSRF-TOKEN`，与 `XSRF-TOKEN` Cookie 做双提交校验；没有该函数时不发送头，请求会被 `CsrfFilter` 拒绝。
- 这些工具不读取 `localStorage` 中的管理端 token，也不调用任何 `/api/admin/*` 接口，不会以管理员身份操作。
- 用户翻译写入（`translate_article`、`save_article_translation`）要求功能开关 `enable_user_post_submission`（`FEATURE_TOGGLES`，设置项“允许用户投稿文章”）开启；关闭时后端返回 `403`，消息为“用户投稿功能暂未开放”。

### 浏览器支持与 PJAX 注意事项

- 能力检测是 `navigator.modelContext || document.modelContext`，并要求 `registerTool` 是函数；不满足时工具完全不可用，调用方不应依赖其存在。
- 工具注册与页面脚本同生命周期：整页导航会重新注册；PJAX 导航不重新执行脚本，但已注册工具仍然有效，且 `get_article_for_translation` 会读取 PJAX 替换后的当前 DOM。
- 不要在 PJAX 片段中重复引入 `webmcp.js`；全局脚本已由 `base.html` 加载，重复引入不会重复注册，但会增加无谓请求。

## Flutter admin MCP

### 启动与配置

`admin-flutter` 在 `dart:io` 平台运行内置 MCP 服务（`mcp_server_io.dart`），Web 平台使用空实现 `mcp_server_stub.dart`。应用启动时 `main.dart` 调用 `FlutterMcpServer.instance.init(api)` 和 `start()`；默认自动启动，默认监听 `127.0.0.1:58180`。主机、端口和自动启动开关保存在 `SharedPreferences` 的 `admin_mcp_host`、`admin_mcp_port`、`admin_mcp_auto_start` 中，并可在“本地应用设置 -> MCP 服务设置”页修改。若修改已运行服务的地址或端口，服务会先停止再按新配置启动。

访问令牌是一个 32 个十六进制字符的字符串，由 `Random.secure()` 生成，保存在 `admin_mcp_token`。设置页的“MCP 访问令牌”卡片显示并允许复制该令牌；“MCP 客户端配置 JSON”会生成带 `Authorization` 头的客户端配置，例如：

```json
{
  "mcpServers": {
    "windblog": {
      "url": "http://127.0.0.1:58180/sse",
      "headers": {
        "Authorization": "Bearer <accessToken>"
      }
    }
  }
}
```

### 传输

服务同时支持旧式 HTTP+SSE 传输和直接 POST：

- `GET /sse`：建立 `text/event-stream`，首个事件为 `event: endpoint`，`data` 指向 `/message?sessionId=<id>`。
- `POST /message?sessionId=<id>`：处理 JSON-RPC 后，把响应作为 `event: message` 写回对应 SSE 流，POST 本身返回 `202` 和 `{"status":"delivered_via_sse"}`。
- `POST /mcp`、`POST /rpc`、`POST /`：直接返回 JSON-RPC 响应。
- `GET /`、`GET /status`：返回服务状态 JSON（`server`、`status`、`host`、`port`、`hasSession`、`user`、`baseUrl`）。

JSON-RPC 版本为 `2.0`，`initialize` 返回 `protocolVersion: "2024-11-05"`、`capabilities.tools` 和 `serverInfo.name: "windblog-flutter-admin-mcp"`；支持 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call`。

### 工具

共 7 个工具。文章查询、翻译和保存工具通过已登录的管理端 `AdminApiClient` 执行；`windblog_get_session_status` 只读取当前会话信息，两个编辑器工具直接操作 Flutter 编辑器状态（其中 `windblog_save_post_translation` 在服务端保存后也会同步到编辑器）。

| 工具 | 类型 | 说明 |
| --- | --- | --- |
| `windblog_get_session_status` | 查询 | 当前管理端登录状态、目标 blog `baseUrl`、令牌是否存在、管理员信息、是否有活动编辑器 |
| `windblog_list_posts` | 查询 | 按 `page`、`size`、`query`、`status`（`ALL`/`PUBLISHED`/`DRAFT`/`PENDING_REVIEW`）列出文章 |
| `windblog_get_post` | 查询 | 按 `postId` 获取文章详情，包括多语言标题、摘要和 Markdown 正文 |
| `windblog_translate_post` | 翻译 | 按 `postId` 和 `targetLanguage` 调用 AI 翻译，可覆盖源 `title`、`summary`、`contentMarkdown`；只返回译文，不保存译本 |
| `windblog_save_post_translation` | 写入 | 保存指定语言的译本；若该文章正在编辑器中打开，同时应用到编辑器 |
| `windblog_get_active_editor_post` | 查询 | 读取 Flutter 文章编辑器当前打开的文章、标题、摘要、正文和 dirty 状态 |
| `windblog_apply_translation_to_editor` | 编辑器写入 | 把翻译结果应用到当前打开的编辑器，不调用服务端保存 |

### 安全模型

- 除 `OPTIONS` 预检外，每个请求都必须带 `Authorization: Bearer <accessToken>`；缺失或错误返回 `401`，响应头带 `WWW-Authenticate: Bearer`，JSON-RPC 错误码为 `-32001`。比较使用常量时间实现。
- `Host` 头必须匹配监听地址：回环监听只接受 `127.0.0.1`、`localhost`、`::1`；非回环监听接受配置的主机名或任意 IP 字面量。不匹配返回 `403`，JSON-RPC 错误码 `-32003`，用于阻止 DNS rebinding。
- `Origin` 头如果存在，必须是 `http` 且主机为 `127.0.0.1`、`localhost` 或 `::1`；否则返回 `403`。CORS 只回显允许的回环 Origin，并带 `Vary: Origin`，不再使用通配符。
- 令牌保护的是本机 MCP 入口，而工具执行使用已登录管理员会话的管理端令牌，因此该 MCP 令牌等同于管理员操作能力。保持默认回环监听，不要把主机改为 `0.0.0.0` 或暴露到网络；该服务没有 TLS。

## Agent discovery

- `GET /auth.md`：`text/markdown; charset=utf-8`，声明站点没有 OAuth 授权服务器、受保护资源元数据或 agent 身份注册流程；用户 API 使用 `user_token` HTTP Cookie；列出 `POST /user/api/register` 和 `POST /user/api/login` 的表单字段，并说明 Cookie 应视为密码。响应带 `Cache-Control: public, max-age=300`。
- `GET /.well-known/api-catalog`：RFC 9727 linkset，媒体类型为 `application/linkset+json`，`anchor` 为 `/search/suggest`，`service-desc` 指向 `/api/public/openapi`（OpenAPI 3.1），`service-doc` 指向 `/api/public/docs`，`status` 指向 `/q/health/ready`。`HEAD` 返回相同的媒体类型和 `Link: <.../.well-known/api-catalog>; rel="api-catalog"`。
- 首页响应带 RFC 8288 `Link` 头：`api-catalog`、`service-desc`、`service-doc` 和 `describedby`。目标使用同源相对路径，以适配区域域名。
- `GET /robots.txt`：包含 `Content-Signal: ai-train=yes, search=yes, ai-input=yes`，`Allow: /`，并禁止 `/admin`、`/api/`、`/q/`，同时给出 `Sitemap`。
- 公开页面支持 `Accept: text/markdown`：`MarkdownNegotiationFilter` 只对首页、分页、文章、分类、标签、搜索、link 相关页面、条款和隐私等公开页面生效；控制器先完成原有语言和访问校验，再把 HTML 转为 Markdown。API、管理页和静态资源不参与协商，响应带 `Vary: Accept`。

## 故障排查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| WebMCP 写工具返回 `403`，`errorCode` 为 `WB-SEC-CSRF-001` | `XSRF-TOKEN` Cookie 缺失、被阻止，或 `X-XSRF-TOKEN` 头缺失/不匹配 | 刷新页面获取新 Cookie；确认浏览器允许 Cookie；确认 `window.getCsrfToken` 存在且读取的是当前页面的 `XSRF-TOKEN` |
| WebMCP 工具返回 `authenticated: false` 或收到 `401`“请先登录” | 当前浏览器没有有效的 `user_token` 会话 | 在同一站点登录后再调用；跨域或第三方 Cookie 被阻止时写入同样失败 |
| 用户翻译返回 `403`“用户投稿功能暂未开放” | `enable_user_post_submission` 功能开关关闭 | 在管理端设置中开启“允许用户投稿文章” |
| Flutter MCP 返回 `401`，JSON-RPC 错误码 `-32001` | 请求没有 `Authorization: Bearer`，或令牌与当前设置页显示的不一致 | 从“本地应用设置 -> MCP 服务设置”复制最新令牌；若令牌已重新生成，更新客户端配置 |
| Flutter MCP 返回 `403`，JSON-RPC 错误码 `-32003` | `Host` 不是监听地址，或 `Origin` 不是回环 `http` 地址 | 使用 `http://127.0.0.1:<port>` 或 `http://localhost:<port>`；不要设置非回环 `Origin` |
| `/a2a/message:send` 返回 `406` | `Accept` 不是 `application/a2a+json` / `application/json` | 显式发送 `Accept: application/a2a+json` |
| `/a2a/message:send` 返回 `400 VERSION_NOT_SUPPORTED` | 缺少 `A2A-Version: 1.0` | 添加该请求头 |
| `/a2a/message:send` 返回 `404 TASK_NOT_FOUND` | 请求带了 `taskId` | 该 agent 无状态，不发送 `taskId` |
| `/a2a/message:send` 返回 `429 PUBLIC_READ_RATE_LIMIT` | 与 `/search` 共用每分钟 120 次的 SEARCH 限流 | 等待 `Retry-After` 指定的 60 秒后重试，或调整 `security.public-read.search-limit-per-minute` |
| `/a2a/message:send` 返回 `415` | `Content-Type` 不受支持 | 改用 `application/a2a+json` 或 `application/json` |

## 本地验证

```sh
curl -i http://127.0.0.1:58080/.well-known/agent-card.json
curl -i -H 'A2A-Version: 1.0' -H 'Content-Type: application/a2a+json' \
  -H 'Accept: application/a2a+json' \
  -d '{"message":{"messageId":"local-1","role":"ROLE_USER","parts":[{"text":"Quarkus"}]}}' \
  http://127.0.0.1:58080/a2a/message:send
curl -i -H 'Accept: text/markdown' http://127.0.0.1:58080/auth.md
curl -i http://127.0.0.1:58080/robots.txt
bash mvnw -Dtest=A2aEndpointTest,AgentCardResourceTest test
```

Flutter 管理端 MCP 可运行 `cd admin-flutter && flutter test test/mcp_server_test.dart`；WebMCP 脚本可用 `node scripts/webmcp.test.cjs` 验证。
