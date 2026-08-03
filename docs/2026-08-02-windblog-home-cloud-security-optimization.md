# WindBlog 家用云优先博客系统：炫技、成本、安全与反盗取改造建议

> 审计日期：2026-08-02
> 审计范围：当前工作树 `D:\codes\windblog_quarkus`，包含 Quarkus 后端、`admin-flutter` 管理端、Liquibase 迁移、Docker 编排、边缘节点和转载追踪链路。
> 文档性质：源码证据驱动的架构与重构建议，不代表其中的建议已经实现。
>
> 部署决策覆盖：基础服务需要对宿主机暴露可配置端口，Compose 默认暴露 PostgreSQL、Redis、RabbitMQ、
> Elasticsearch 和 Kibana 的常用端口。这里的“对外”指宿主机/管理网可访问，不代表允许直接暴露互联网；
> 生产必须使用防火墙、VPN 或来源 allowlist 限制这些端口。

> 实现状态（2026-08-03）：本工作树已落地短期文章访问票据、受保护媒体下载与审计/限流、统一 SSRF 出口、
> 管理 resource/action 权限、敏感 DTO、强制 CSP/HSTS 生产启动门禁、显式 CSP 外部 origin allowlist、
> PostgreSQL FTS fallback、outbox、病毒扫描门禁、公开文章 v4 read model（文章详情与首页分页投影）、
> 边缘公开白名单批次同步和基础服务可配置宿主机端口。生产 CSP 已加入 Trusted Types 默认净化策略，公开
> 动态 HTML 不再使用内联 style 属性；密码保护读链路只接受解锁后短期票据，受保护页面和下载均禁止共享缓存，
> 实际下载流也有单文件字节上限；Redis 可用时下载请求数、字节预算和并发租约使用分布式计数，Redis 故障时
> 有界降级到本地保护；用户中心背包列表改为批量加载。仍未把真实 CDN、反向代理、ClamAV、
> 多节点一致性、IPv6、磁盘满和 3-2-1 备份恢复演练的结果虚构为已完成；仓库已提供强制 GPG 加密备份和隔离恢复验证脚本，
> 但这些项目必须在真实部署环境继续验收。

## 1. 目标与结论

目标不是把博客改造成“永远不可复制”的系统——浏览器必须收到能显示的文字和图片，截图、录屏、手工抄写无法从技术上彻底阻止。可行目标是把盗取成本提高到明显高于正常阅读成本，并让每一次疑似批量抓取、资源直链盗用、异常转载和凭证滥用都可限流、溯源、撤销和恢复。

建议将系统定位为：

1. **主节点是家用云的唯一业务真源**：PostgreSQL、本地文件盘、RabbitMQ、Elasticsearch、Kibana 和管理端放在家中；主节点不直接暴露公网。
2. **公网访问固定经过 CDN → 边缘节点 → 主节点**：CDN 负责公开内容缓存和基础清洗，边缘节点负责公网入口、WAF/限流/反爬和回源控制，家里云主节点只接受边缘节点的安全回源连接。
3. **炫技能力是独立的控制面**：Elasticsearch、RabbitMQ、AI、邮件中心和多存储作为可展示的扩展；边缘节点是固定公网接入层。各组件与公开文章阅读链路保持清晰边界，故障时仍应有明确的安全降级路径。
4. **安全能力集中到少数共享边界**：认证、可信代理 IP、CSRF、限流、外部请求、媒体下载授权、审计和密钥脱敏都应只有一个实现。
5. **反爬不是只写 `robots.txt`**：机器人提示用于礼貌性约束；真正的保护由响应分层、速率/行为模型、短期签名、来源绑定、水印、撤销和异常证据链共同完成。

当前最重要的判断是：项目已经有不少“高级零件”，但安全边界仍是分散的，且存在几个不能带病公网运行的缺口。优先级应先放在凭证和付费资源泄露、SSRF 旁路、管理面暴露和多实例限流，再完善 CDN、边缘节点和搜索之间的安全协作。

## 2. 当前源码架构画像

### 2.1 请求与数据主链路

```text
浏览器/爬虫
    │  443 反向代理（当前仓库未提供统一公网入口策略）
    ▼
Quarkus REST + Qute SSR
    ├─ 公共页面：IndexController / PostController / SearchController / FeedController
    ├─ 用户 API：UserController / CommentApiController / UserPostContentController
    ├─ 管理 API：/api/admin/* + AdminJwtAuthFilter + AdminRequestContext
    ├─ 媒体：MediaManagementService → LocalFs / Aliyun OSS → /uploads 或 CDN
    ├─ 缓存：CacheService → Redis
    ├─ 异步：AI、ES、媒体同步、存储同步、邮件 → RabbitMQ/调度器
    └─ 边缘：主节点 PostgreSQL 数据 → gRPC mTLS / 持久通道 → edge 只读节点
```

后端以 Panache Active Record 为主，控制器中仍有大量查询、DTO 组装和策略判断；`MediaManagementService.java`、`ImportService.java`、`PostAccessService.java` 和 `AdminPostApiController.java` 已经是高耦合大类。`PostRepository.java` 还明确写着当前未接入调用链。这种结构短期开发快，长期会导致“页面访问、API 访问、边缘访问、管理预览”各自实现一套安全判断。

### 2.2 当前已有的可复用能力

| 能力 | 当前实现 | 可保留方向 |
|---|---|---|
| 管理认证 | `AdminJwtAuthFilter` 对 `/api/admin/*` 统一要求 Bearer JWT，登录、文档和 OpenAPI 例外 | 收敛为显式权限策略和短时令牌/刷新令牌 |
| 用户密码 | `PasswordHasher` 使用 PBKDF2-HMAC-SHA256，并有旧明文兼容 | 迁移完旧值后去掉明文兼容分支 |
| CSRF | `CsrfFilter` 使用双重 Cookie 提交，前台写操作检查 `X-XSRF-TOKEN` | 与认证模式、Cookie 属性、缓存策略统一 |
| SSRF 部分防护 | `SafeExternalHttpService` 校验公网地址、关闭自动重定向、限制 1 MiB 响应 | 让所有外部 URL 访问都走这一边界 |
| 媒体处理 | 上传角色、扩展名禁用、大小配额、部分 Magic Number、图片/视频派生版本 | 改为异步工作流并使用真实内容探测/隔离转码 |
| 多存储 | `StorageService` 维护主存储、备份存储、variant 和同步状态 | 把“公开 URL”和“授权 URL”分成不同接口 |
| 转载追踪 | 授权码、token hash、允许域名、风险设备、点击证据、语义水印配置 | 改成分布式限流和真正的内容泄露检测闭环 |
| 边缘节点 | gRPC mTLS、证书吊销/过期检查、只读写保护、同步记录 | 作为固定公网接入层；只缓存公开内容，不保存核心业务真源 |
| 缓存 | Redis 对预览、侧栏和页面片段缓存，Redis 不可用时降级 | 将缓存分为公开可缓存和绝不缓存两类 |

## 3. P0/P1 现状风险清单

严重度含义：P0 表示修复前不应把相应功能暴露到公网；P1 表示应在首次公网运行前完成；P2 表示在重构或规模增长时完成。

### 3.1 P0：付费内容和内部网络

#### P0-1 文章密码会进入明文 Cookie，且密码 Cookie 没有真正的访问票据语义

证据：

- `src/main/java/com/biliwind/blog/controller/api/UserPostContentController.java:273-287` 的注释和实现明确从 `post_pw_{postId}` Cookie 读取用户上次验证的明文密码，再交给 `PostAccessService.verifyPassword`。
- `Post` 实体的 `password` 字段只保存哈希是正确方向，但明文会在浏览器 Cookie、代理日志、导出 Cookie、浏览器备份和潜在 XSS 场景中扩散。
- `PostPasswordFilter` 只解析 `X-Post-Password` header；页面过滤器与安全内容 API 的授权方式不一致。

建议：

1. 密码提交成功后服务端生成随机、短期、可撤销的 `post_access_ticket`，数据库或 Redis 只保存 HMAC/哈希、postId、过期时间、设备/会话绑定和失败次数；Cookie 只保存票据，不保存密码。
2. 票据使用 `HttpOnly; Secure; SameSite=Lax`，并按文章区分 Cookie Path 或使用 Authorization header；密码本身只存在一次 HTTPS 请求内。
3. 页面、PJAX、`/api/user/post/content`、`/blocks`、购买接口和附件下载统一调用 `PostAccessPolicy`，不要继续分别判断 header/Cookie。
4. 明文兼容迁移完成后，删除对明文文章密码的 `PasswordHasher.matches` 兼容分支和相关历史数据；无法迁移的文章标记为需重设。

#### P0-2 付费附件可能退化为公开 URL，Local FS 的“签名 URL”并不签名

证据：

- `PostController.java:214-216` 先取 `getBestSignedUrl`，失败后回退到 `pm.media.url`。
- `UserPostContentController.java:104-112` 和 `:184-192` 在购买后直接返回 `pm.media.url`。
- `LocalFsStorageClass.java` 的 `getSignedUrl` 直接返回 `getPublicUrl`，本地存储不存在授权签名。
- `application.properties:265-266` 为 `/uploads/*` 设置了 30 天公开缓存；`UploadFileController.java:77` 对本地文件返回一年 `immutable` 缓存。

影响：只要资源路径可猜、从 HTML/接口/Referer 泄露或被复制，付费附件和原图可以绕过购买检查直接下载。对家用云而言，这还会把原图下载和盗链流量全部打到家庭上行。

建议的资源分层：

| 资源 | URL 形态 | 缓存 | 授权 |
|---|---|---|---|
| 公开缩略图/占位图 | 不可枚举的内容哈希路径 | CDN 长缓存、immutable | 公开 |
| 公开文章内图片 | CDN 公开 URL，可选签名防盗链 | 可缓存 | 文章可见性/区域 |
| 原图、附件、付费图片 | `/api/media/download/{opaqueId}` 或对象存储私有 key | `no-store` 或极短私有缓存 | post ticket + purchase + region |
| 管理原图 | 管理 API 生成一次性下载票据 | `no-store` | admin permission + audit |

`StorageClass` 接口应拆成 `publicAssetUrl` 与 `authorizedDownloadUrl`；Local FS 不能伪装成 signed URL，必须由 Quarkus 流式代理校验票据，或在反向代理层使用内部重定向。任何授权失败都不能回退到 `media.url`。

#### P0-3 外部媒体导入存在 SSRF 防护旁路

证据：

- `SafeExternalHttpService.java:25-190` 是相对完整的公网请求实现，限制 scheme、解析地址、拒绝非公网、关闭自动重定向并限制 1 MiB 响应。
- 但 `MediaManagementService.java:581-629` 仍自行使用 `HttpURLConnection`，重复解析 URL，`setInstanceFollowRedirects(true)`，并在每次循环前调用较弱的 `MediaSecurityHelper.isPrivateOrLoopbackAddress`。
- `AdminImportApiController.java:39-50` 接收超级管理员可控的 `ImportRequest`；DTO `AdminImportDtos.java:28-32` 直接暴露 `allowLocalNetwork`。

影响：DNS 重绑定、重定向到内网、超大响应、协议边界和本地网络访问策略不一致。即使只有超级管理员能触发，也不能把内部网络读取能力做成普通请求字段。

建议：

1. 删除 `MediaManagementService` 的 `HttpURLConnection` 实现，统一注入 `SafeExternalHttpService` 或一个专门的 `SafeMediaFetcher`。
2. 每个重定向都重新解析并校验最终 IP；固定最多 3 次；禁止自动跟随；限制响应头声明长度、实际流长度、压缩解压后长度、下载时间和 Content-Type。
3. `allowLocalNetwork` 不作为 API 布尔字段。若确需导入家中 NAS，建立显式的服务器端 allowlist（CIDR/主机名/端口）、一次性管理员确认和独立审计事件，默认永远关闭。
4. 对外部 AI endpoint、链接监测、文章外链抓取也建立同一个 egress policy；“可配置 AI endpoint”不能默认允许任意 `http://127.0.0.1`。

### 3.2 P1：公网边界、管理面和内容安全

#### P1-1 CORS、Cookie 和 Swagger 默认不适合公网

证据：

- `application.properties:87` 的 `cookie.secure=false`，用户认证 Cookie 与 CSRF Cookie 会在非 HTTPS 配置下工作。
- `:249-256` 全局启用 CORS，开发环境接受任意来源，生产默认来源为 localhost，同时允许 credentials，并暴露 `Set-Cookie`。
- `:90-93` `quarkus.swagger-ui.always-include=true`，管理文档路径由 `AdminJwtAuthFilter.java:47-50` 明确豁免认证。
- `SecurityHeadersFilter.java` 使用 `Content-Security-Policy-Report-Only`，且允许 `unsafe-inline`，不是强制 CSP。

建议：

- 生产只允许明确的管理端域名；公开博客页面不需要 credentials CORS。删除 `Set-Cookie` 的 exposed header，除非经过明确设计。
- 生产强制 `cookie.secure=true`，并设置统一的 `__Host-` 前缀（如部署条件允许）。语言 Cookie 也不要在 `LangResponseFilter.java` 中硬编码 `secure(false)`。
- Swagger/OpenAPI 默认关闭；需要时仅允许 VPN/内网或单独的管理域名，并增加文档访问审计。
- 将 `Report-Only` 改为强制 CSP。先通过 nonce/hash 消除模板内联脚本，再收紧 `script-src`、`style-src`、`img-src`、`connect-src`；文章 HTML 与管理 HTML 使用不同 CSP。
- 反向代理负责 HSTS、HTTP→HTTPS、真实客户端 IP 和请求体上限，应用继续保留一层防御；不要仅靠 `Host`/`X-Forwarded-For`。

#### P1-2 管理 API 虽有统一 JWT 过滤器，但权限模型仍不够显式

`AdminJwtAuthFilter` 统一拦截 `/api/admin/*` 是好基础，但多数控制器只靠 `adminRequestContext` 是否有用户、`isSuperAdmin` 或局部 `mustFindOperator()` 判断。OpenAPI 的 `@SecurityRequirement` 只是文档元数据，不是授权执行器。

建议建立：

```text
AdminAuthentication → AdminAuthorization(resource, action) → DomainPolicy → Audit
```

例如 `media.read`、`media.write`、`post.publish`、`settings.secret.read`、`edge.issue_certificate`、`database.import`、`dead_letter.replay` 应成为权限动作。危险操作增加二次确认、短时 step-up token、幂等键和审计前后快照。数据库导入、证书签发、队列发布、系统设置和死信重放不能仅因为“是管理员”就全部可用。

#### P1-3 敏感实体/设置/同步 payload 有泄露面

证据：

- `User.java` 的 `password` 是 public 字段且没有 `@JsonIgnore`。
- `EdgeDataSyncService.java` 的 `syncUser` 直接 `objectMapper.writeValueAsString(user)`，并在 `EdgeSyncDataApplyService.java` 写回 `incomingUser.password`。
- `AdminSystemSettingsController.java` 的 `getAllSettings` 和 `getSettingByKey` 直接返回 `SystemSetting`，其中 `configValue` 可能包含 AI key、ES 密码、邮件密码或其他 secret；当前 `sanitizeForAudit` 只作用于审计值，不作用于普通响应和边缘同步。
- `AiProviderConfigDtos` 有脱敏逻辑，说明项目已经意识到问题，但设置、用户同步和若干实体没有统一 DTO 边界。

建议：

1. 所有实体禁止直接作为 REST 或同步 payload；为 `UserPublicView`、`UserSyncView`、`SystemSettingView`、`MediaAdminView` 建立白名单 DTO。
2. `UserSyncView` 只发送 id、状态、角色和必要显示信息，边缘节点不需要密码哈希；边缘认证改为节点级身份，不复制用户凭证。
3. 设置定义增加 `secret=true`、`syncScope`、`readPolicy` 元数据；读取接口只返回 `configured=true`、掩码和版本，更新采用“留空不变”。
4. 对所有 JSONL、审计、异常和 SSE 输出做敏感字段递归脱敏，并增加序列化快照测试，确保新字段不会自动泄露。

#### P1-4 文章 HTML/富文本能力与 CSP 不匹配

`MarkdownHelper.java:16-46` 使用 Jsoup 过滤是正确方向，但允许 `style`、`div`、`svg`、`button` 和大量 `data-*` 属性；`PostRenderType` 还支持 HTML、Vditor、Quill 等非 Markdown 路径。强制 CSP 尚未开启，且当前 CSP 允许内联脚本/样式。

建议把渲染内容分为三档：

- **安全 Markdown**：服务器统一渲染，允许有限标签和自有媒体域名，删除 style、事件属性、任意 iframe、form、SVG 外链和 data URL（确需 data 图片时仅允许受限 MIME/大小）。
- **可信作者 HTML**：只允许受信管理员发布，存储净化后的 HTML，不能与管理域同源；可以使用独立 `content.example` 域名和更严格响应头。
- **原始编辑数据**：只在管理端返回，永不直接渲染给公开访客。

所有链接强制 `rel="nofollow noopener noreferrer"`；所有图片通过媒体解析器归一化，禁止任意外部图片直接消耗家用云带宽，或明确标记为“外链不享受防盗链”。

#### P1-5 反爬链路有基础，但当前不能称为“防盗”

已有实现：

- `AffiliateTokenService` 保存 token hash，不保存原始 token；`RepostLicenseService` 写入授权域名与语义水印配置。
- `GoRedirectService` 记录 Referer、UA hash、IP hash、风险设备、风险分数，并返回 `X-Robots-Tag: noindex, nofollow` 与 `Cache-Control: no-store`。
- `RepostDetectionService` 聚合未知域名证据；迁移 `072-create-repost-affiliate-tracking.sql` 建立了 token、点击、可疑转载、封禁域名和风险设备表。

缺口：

- 这套链路主要保护“转载授权/短链”，不是所有文章 HTML、原图和附件下载。
- `GoRedirectService.java:26` 使用 `HashMap` 保存限流窗口，`isRateLimited` 只在单 JVM 生效，多个实例/边缘节点可绕过；并发访问也不是线程安全的。
- `GoRedirectService.java:238-247` 直接读取 `X-Forwarded-For`/`X-Real-IP`，没有复用 `ClientIpResolver` 的可信代理网段判断，攻击者可伪造来源 IP。
- 只依赖 Referer、UA、`X-Device-Risk-Id` 不足以证明请求合法；这些头可以被脚本伪造或浏览器隐私策略省略。
- `robots.txt` 的 `Allow: /` 只是爬虫礼貌协议，不能保护资源。

建议的内容防盗分层：

1. **边缘入口**：按 IP、ASN/代理信誉、会话、文章、资源类型做 Redis/网关限流；对搜索、登录、评论、下载、短链分别设置预算。
2. **文章页**：公开正文可以缓存，但只发安全预览；登录/购买后的完整内容通过短时票据 API 分块返回，并限制并发、每分钟字节数和异常翻页。
3. **媒体**：原图/附件短时签名且绑定资源、过期时间、方法、可选 IP/UA hash；签名服务支持撤销版本和密钥轮换。图片变体默认只给低分辨率或带轻微可见水印的版本。
4. **行为检测**：记录匿名化的请求序列、并发数、页面到下载的时间、同一 token 的资源覆盖率、403/404 比例和字节消耗；检测到“遍历所有 slug + 高频原图”时进入挑战、降级或封禁。
5. **追踪水印**：每次购买/转载生成不同的可见/不可见标识；文章句式变体可以作为取证辅助，不能单独作为授权控制。
6. **响应头**：公开页面加入合理 `X-Robots-Tag`，受保护下载 `no-store`、`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`，并确保 CDN 不缓存带用户授权结果的响应。

### 3.3 P2：可用性和可维护性

#### P2-1 Docker 管理端口暴露过宽

`docker-compose.yml` 同时启动 PostgreSQL、Redis、RabbitMQ（带 management）、Elasticsearch、Kibana、Filebeat；这些组件可以作为完整的家用云部署栈运行，但 PostgreSQL、Redis、RabbitMQ、Elasticsearch、Kibana 的端口均映射宿主机，Redis 还以 `--bind 0.0.0.0` 启动。

家用云部署允许 PostgreSQL、Redis、RabbitMQ、Elasticsearch 和 Kibana 使用可配置的宿主机端口，便于宿主机管理工具和独立应用连接；这些端口必须由宿主机防火墙限制到管理网、VPN 或明确的内网来源，不能直接暴露公网。服务之间仍通过内部 Docker network 通信，应用管理端口与公网入口保持分离。部署仍应设置 CPU/内存上限、日志磁盘上限和健康检查，防止异常任务、日志缓冲和媒体转码相互争抢资源。

#### P2-2 Elasticsearch、RabbitMQ 和自建边缘同步不能阻断公开阅读

当前 ES 有异步初始化和降级；搜索控制器在 ES 不可用时会回退到 `SearchController.buildHits`，该路径会加载已发布文章列表到 JVM 再过滤/分页。文章量增长后，这会带来数据库和堆内存压力，并放大爬虫请求影响。

建议：

- PostgreSQL 增加全文搜索向量、GIN 索引、前缀建议表；把 ES 不可用时的 fallback 变成有数据库分页的正式实现，而不是“全量 list 后内存筛选”。
- Elasticsearch 作为正式搜索能力运行，同时保留索引重建、磁盘水位和故障切换策略；搜索服务异常时不能影响公开文章读取。
- RabbitMQ 继续承载邮件、AI、媒体等异步任务；DB outbox、租约和 scheduler 用于保证消息投递、重试和恢复，不替代 RabbitMQ。消息消费异常时应隔离失败任务，不能让公开请求同步等待。
- 边缘节点只同步公开发布内容和必要的撤销清单；不能同步密码、secret 和无关的用户数据。边缘没有原始付费资源，所有授权下载回源主节点或对象存储。

#### P2-3 事务、异步和数据同步边界需要重构

现状问题：

- 控制器、服务和实体混合使用 `@Transactional`；媒体上传在请求中写盘、检测、生成变体和写数据库，视频处理可能阻塞请求线程。
- `EdgeDataSyncService` 的完整同步逐个实体序列化、重试并阻塞等待，文章又逐篇查询标签，规模变大后会形成长事务和 N+1。
- `EmailDeliveryService.deliverPendingMessages` 在一次事务中取出所有 pending 邮件并逐个 SMTP 发送，缺少租约/并发领取；多副本会重复发送。
- `SafeModeWatchdog`、ES 日志缓冲、存储重试和边缘连接各自创建线程/调度器，缺少统一 shutdown、队列容量和指标。

目标是明确四类边界：

1. DB 事务只负责状态和 outbox 事件，不能包含网络请求、SMTP、对象存储和长时间转码。
2. 工作任务使用 `job_id`、幂等键、租约、attempt、next_run_at、dead-letter 和最大字节/时间预算。
3. 公开读取只访问 read model/缓存，不触发同步、AI 或媒体处理。
4. 同步使用版本号/游标/批量快照，失败可重放；不要把每个实体的序列化异常直接扩散成全量同步失败。

#### P2-4 查询和 DTO 需要按热点拆分

优先关注：

- `UserGamificationController` 和用户中心对背包逐项 `findById`，形成 N+1。
- `RegionRuleService` 每次请求都加载所有启用规则；应使用版本化缓存。
- `MediaManagementService.toDto` 的单媒体兼容入口仍会查询上传者；媒体列表主路径已批量加载上传者，后续可继续把单媒体入口改为显式传入批量上下文。
- 文章页重复加载 tags、media、购买记录和权限；建立 `PublishedPostReadModel` 和批量 media reference 查询。
- 全量媒体引用扫描会删除全部 `PostMedia` 后重新遍历所有文章，应该改为离线任务、分批提交和可恢复进度。
- `AdminSystemSettingsController` 返回全量设置/历史，没有字段级分页、权限过滤或敏感数据投影。

数据库建议补充并验证实际执行计划：

- `posts(status, deleted_at, published_at desc)`、`posts(visibility, status, deleted_at)` 的部分索引。
- `post_revisions(post_id, revision_number desc)`、`post_media(media_id)`、`comments(post_id, status, created_at)`。
- `media(storage_key) where deleted_at is null` 唯一索引和下载审计索引。
- purchase、download ticket、rate-limit/event outbox 的复合索引。
- 对 JSONB 只在有查询谓词时保留 GIN，不要把 JSONB 当作所有核心关系的替代品。

## 4. 推荐目标架构

### 4.1 家用云公网拓扑

```text
Internet
  │
  ▼
CDN / WAF
  ├─ 公开 HTML / RSS / 变体图片：缓存
  └─ 受保护下载 / 动态 API：不缓存，回源边缘节点
  │  CDN origin 仅指向边缘节点
  ▼
公网边缘节点
  ├─ TLS origin、WAF、反爬、限流、请求体/带宽预算
  ├─ 公开内容边缘缓存
  ├─ 受保护下载票据校验或安全回源
  └─ mTLS / 私有隧道 → 家用云主节点
  ▼
家用云主节点（业务真源）
  ├─ 反向代理：只接受边缘节点或管理 VPN 的连接
  ├─ blog-web:8080       Quarkus
  ├─ admin: 管理 VPN / allowlist / 独立管理域名
  ├─ postgres / Redis / RabbitMQ / Elasticsearch / Kibana：宿主机可配置端口，防火墙限制来源
  └─ local storage：私有卷，不直接作为公开静态资源
```

边缘节点是固定的公网接入层，不复制 PostgreSQL、RabbitMQ、Elasticsearch 或原始付费资源；它只保存公开缓存、必要的撤销清单和最小化风控状态。公开内容可以在 CDN 和边缘节点缓存，登录、购买、原图和附件请求必须沿边缘节点回源主节点或受控对象存储，不能让 CDN 直接绕过边缘访问家庭网络。

### 4.2 代码模块边界

建议逐步形成以下模块，不要求一次性大重构：

```text
web/
  PublicReadResource       公开文章/feeds/搜索
  ProtectedContentResource 付费/密码/附件票据
  MediaDownloadResource    原图/附件授权下载
admin/
  AdminAuthResource
  AdminPostResource
  AdminMediaResource
  AdminSecurityResource
domain/
  PostAccessPolicy
  MediaAccessPolicy
  AntiAbusePolicy
  RegionPolicy
application/
  PublishPostUseCase
  IssueDownloadTicketUseCase
  ImportMediaUseCase
  RebuildMediaReferencesUseCase
infra/
  PublicAssetStorage
  PrivateAssetStorage
  SafeHttpFetcher
  DistributedRateLimiter
  OutboxPublisher
```

控制器只做输入校验、调用 use case 和映射 DTO；策略服务输出结构化决策，例如 `ALLOW`、`DENY`、`CHALLENGE`、`READ_ONLY` 以及 reason code。这样公开页面、用户 API、管理预览、边缘回源不会再各自复制权限判断。

### 4.3 公开内容与受保护内容的契约

统一定义：

- `PublicPostView`：标题、摘要、安全预览 HTML、缩略图、声明、公开元数据；不含完整付费正文、原图 URL、内部 id、用户购买状态。
- `ProtectedPostView`：必须携带用户会话或 post access ticket；内容按 block/segment 返回，响应 `no-store`，不进入公共 Redis/CDN。
- `DownloadTicket`：资源 opaque id、用途、授权主体、区域、签发时间、过期时间、版本、jti；服务端只保存 hash/撤销版本。
- `MediaPublicView`：只允许缩略图/占位图；管理 DTO 不能直接返回 storage config、secret 或内部物理路径。

## 5. 分阶段实施计划

### 阶段 A：公网止血（P0，先做）

1. 生产启动拒绝 `cookie.secure=false` 和未配置的公网 URL。
2. 从所有文章 API 和页面中删除明文密码 Cookie；上线短期 post ticket。
3. 禁止付费附件返回 `media.url`；Local FS 授权下载走 Quarkus/反向代理内部流，所有失败不 fallback 到公开地址。
4. 合并所有媒体/链接/导入外部请求到 `SafeMediaFetcher`；删除 `allowLocalNetwork` 公共布尔开关。
5. 关闭公网 Swagger；生产 CORS 改为显式 allowlist；管理端只在 VPN/allowlist 可达。
6. Docker 基础服务提供可配置的宿主机端口，至少覆盖 PostgreSQL、Redis、RabbitMQ management、ES 和 Kibana；
   生产必须用防火墙/VPN/allowlist 阻止互联网直接访问这些管理端口。
7. 给 `User`、`SystemSetting`、边缘同步增加白名单 DTO 和敏感字段回归测试。

阶段 A 的硬验收：

- 未购买用户拿到文章 HTML、API JSON、页面源码、网络面板中的任何 URL 后，不能直接下载原图/附件。
- `post_pw_*` Cookie 不再出现；日志、审计、SSE、边缘同步不含 password/apiKey/secret。
- SSRF 测试覆盖 loopback、RFC1918、IPv6 ULA/link-local、DNS rebinding、跨协议重定向、重定向到内网、超大响应和压缩炸弹。
- 从家庭网络外部只能看到 CDN/边缘节点入口；家庭主节点不直接暴露，管理登录、Swagger、数据库和消息系统不能直接访问。

### 阶段 B：统一安全控制面（P1）

1. 引入 `SecurityPolicy`/`PostAccessPolicy`/`MediaAccessPolicy`，消除 Controller 与 Service 之间的重复判断。
2. 管理权限按 resource/action 落地，危险操作 step-up + 幂等 + 审计。
3. 用 Redis 或反向代理实现分布式限流；本地内存仅作为故障时的保护性上限。
4. 统一可信代理 IP 解析，所有短链、审计、限流、风控和日志使用同一结果。
5. 强制 CSP、Trusted Types（前端可行时）、独立内容域名和媒体域名；文章渲染档位化。
6. 建立下载行为检测：单 IP/会话/票据/文章/资源的请求数、字节数、并发、命中率和来源覆盖率。
7. 资源签名 key 支持版本和撤销；泄露时可以只撤销某篇文章、某个用户、某个设备或某一版本。

### 阶段 C：性能与数据重构（P2）

1. 公开阅读链路改为 read model + Redis/CDN；文章发布时异步生成预览 HTML、搜索文档和媒体引用。
2. PostgreSQL FTS/GIN 作为 Elasticsearch 故障时的正式 fallback；保持 ES 搜索索引与重建任务可观测、可恢复。
3. 媒体处理拆为上传、病毒/类型检查、变体生成、存储复制、索引五个幂等任务。
4. 邮件、AI、ES、存储和边缘同步统一 outbox/worker 约定，限制队列和本地落盘大小。
5. 消除列表页 N+1、全量内存搜索、全量媒体扫描和无分页的设置/历史接口。
6. 先跑稳定版本，再以静态缓存/对象存储复制补充异地能力；所有基础设施都应有健康检查、告警和恢复演练。

### 阶段 D：可展示的炫技功能（风险可控）

可在安全基础上展示：

- 文章发布事件流：发布 → 预览生成 → 缩略图 → 搜索索引 → CDN purge → 邮件通知，后台显示每个任务的 trace id 和可重放状态。
- 资源安全面板：展示下载票据、签名版本、命中/拒绝原因、盗链趋势和自动封禁解释。
- 边缘节点看板：只读缓存命中率、回源延迟、同步游标、撤销清单传播时间；敏感数据不下沉。
- 成本面板：本地磁盘占用、对象存储副本字节、CDN 回源字节、ES/RabbitMQ 内存和每篇文章的处理成本。
- AI 仅作为摘要、标签、异常解释和草稿建议；发布与封禁等高风险动作保留人工确认和结构化 reason。

## 6. 数据库与迁移建议

### 6.1 建议新增的核心表

```text
content_access_ticket
  id, token_hash, subject_type, subject_id, post_id, scope, region,
  expires_at, revoked_at, key_version, created_at, last_used_at

media_download_event
  id, media_id, post_id, subject_hash, ticket_id, ip_hash, ua_hash,
  bytes_sent, status, deny_reason, node_id, created_at

abuse_counter_bucket
  bucket_key, bucket_start, request_count, byte_count, blocked_until

outbox_event
  id, event_type, aggregate_type, aggregate_id, payload_version,
  idempotency_key, status, attempt_count, next_attempt_at, locked_until
```

令牌、IP、UA 只保存不可逆 hash 或带轮换盐的 hash；原始 Referer 只有在明确取证需要时保存，并设置短保留期。下载事件应设置分区/归档策略，不能让反爬日志反过来耗尽家用磁盘。

### 6.2 迁移纪律

- 每次迁移单独 changeset，禁止修改已执行的旧 SQL；当前 `db.changelog-master.xml` 的迁移文件编号不连续，新增应继续用新编号并注册。
- 先加 nullable 字段和双写，再回填、切读、最后加约束；家用云停机窗口有限，不做一次性锁表大迁移。
- 为票据、下载权限、敏感 DTO、资源撤销和 outbox 各补 unit/IT；测试必须覆盖软删除、区域、购买、密码票据过期和边缘只读。

## 7. 观测、备份与恢复

### 7.1 必须有的指标

| 类别 | 指标 |
|---|---|
| 公开阅读 | 5xx、P95/P99、DB 查询耗时、缓存命中、文章预览生成耗时 |
| 安全 | 登录失败、JWT 撤销、CSRF 拒绝、SSRF 拒绝、下载拒绝、挑战/封禁数 |
| 反盗 | 每会话请求数、字节数、文章遍历比例、原图/缩略图比例、来源域名风险 |
| 存储 | 本地磁盘水位、孤儿文件、媒体处理失败、同步 pending/failed、备份年龄 |
| 异步 | outbox lag、队列深度、重试次数、死信数、重复幂等命中 |
| 边缘 | 最后心跳、同步游标差距、撤销清单传播延迟、回源失败率 |

指标中不要放原始密码、JWT、API key、完整 Cookie、签名 URL 或完整 IP。日志保留分层：安全告警短期高精度，普通访问聚合后保留；家庭云磁盘有限时优先保留可恢复性数据和安全证据。

### 7.2 家用云备份最低方案

3-2-1 是目标：主机、同机/外接备份、异地或云端一份；PostgreSQL 使用定期 base backup + WAL（若资源允许），媒体采用内容哈希去重和增量同步。备份必须加密，恢复演练至少每季度一次，验证：文章、用户、购买记录、媒体引用、密钥轮换、边缘撤销状态都能恢复。

## 8. 验证清单（完成定义）

### 安全测试

- [ ] 默认配置、默认密码、弱 secret、未启用 HTTPS 的生产启动失败。
- [ ] 管理 API 的每个 resource/action 都有未授权、越权、已禁用用户、过期 token、撤销 token 测试。
- [ ] CSRF 在所有 Cookie 认证写接口生效；Bearer-only API 不依赖 Cookie。
- [ ] 文章密码 never leaves server：请求后 Cookie、响应、日志、前端存储均无明文密码。
- [ ] 付费附件、原图、被软删除媒体、区域隐藏媒体均不能通过备用 URL、旧 URL、variant URL 绕过。
- [ ] SSRF 覆盖 DNS 解析变化、IPv4/IPv6 特殊地址、重定向、非 HTTP scheme、超大响应和内网 allowlist。
- [ ] CORS 只允许预期来源；生产 Swagger、数据库、Redis、RabbitMQ、ES 管理端不可从公网直接访问，
  即使宿主机端口已映射也必须由网络策略隔离。
- [ ] Markdown/HTML/Quill/Vditor/XSS payload、SVG、style、data URL、外链图片全部按档位验证。

### 反爬与容量测试

- [ ] 单会话低速阅读正常；高频分页、全量 slug 遍历、并发原图、跨文章票据复用会被限流/挑战。
- [ ] 多 Quarkus 实例/边缘节点共享限流和撤销状态；节点重启后策略仍有效。
- [ ] CDN 不缓存用户授权响应，签名 URL 过期、撤销、方法不匹配和资源不匹配均失败。
- [ ] 测试家庭上行在下载洪峰下的降级：缩略图仍可读，原图排队或拒绝，管理端可用。
- [ ] 反爬证据可按文章、用户、token、域名和时间窗口查询，且数据有保留上限。

### 生产运行验收

- [ ] 完整部署栈启动后，公开文章、登录、管理、上传基础功能可用；搜索、异步任务和日志组件分别可观测。
- [ ] Redis、RabbitMQ、ES 或 AI 故障时，公开安全预览仍可用，功能明确降级而非 500 风暴。
- [ ] 边缘节点或回源隧道故障时，CDN 已缓存的公开内容可继续服务；动态/受保护请求明确失败或降级，不能绕过边缘直连家庭云。
- [ ] 断电/容器重启后 outbox、媒体任务、邮件任务、备份和锁租约可恢复，不重复扣费、不重复发信。
- [ ] 发布、媒体处理、搜索、CDN purge、边缘同步可通过 trace id 关联并重放。
- [ ] 真实家用网络验证反向代理、IPv6、证书自动续期、备份恢复、磁盘满、上游 DNS 变化和限流，不用“编译通过”替代运行验收。

## 9. 建议的第一批实现顺序

如果只安排一个短迭代，按以下顺序最划算：

1. `PostAccessPolicy` 与短期 post ticket，清理明文密码 Cookie。
2. `MediaDownloadResource`，删除所有付费资源 URL fallback；Local FS 不再伪造签名 URL。
3. `SafeMediaFetcher` 合并 SSRF 路径，移除 `allowLocalNetwork` 请求字段。
4. 敏感 DTO：用户、设置、媒体、边缘同步；补序列化泄露测试。
5. Redis 分布式限流 + 可信代理 IP 统一；把 GoRedirect 的 `HashMap` 限流改掉。
6. 用 PostgreSQL FTS 替代 ES fallback 的全量内存搜索，同时保留 Elasticsearch 作为正式搜索能力。

## 10. 审计边界与未完成事项

本次文档基于源码、迁移和编排文件静态审计。没有把当前环境中的编译、数据库迁移、反向代理、真实 CDN、家庭路由器、对象存储账单、跨节点下载和恢复演练结果虚构成已验证事实。尤其以下项目仍需运行时验收：

- 真实生产数据库中的索引/执行计划和迁移锁表时间；
- HTTPS 反向代理的真实 `X-Forwarded-For`、WebSocket/SSE、HTTP/2 和缓存行为；
- CDN/对象存储是否会缓存或公开原图；
- 多副本下 RabbitMQ、Redis、邮件、下载票据和边缘撤销的一致性；
- 家用上行带宽、磁盘寿命、转码 CPU/内存和断电恢复；
- 公开文章对真实浏览器、搜索引擎和常见抓取器的行为检测误报率。

最终完成标准不是“新增了多少安全类”或“启动了多少组件”，而是：普通公开阅读足够快、家用云成本可控；付费/原图/管理资源的每条路径都经过同一授权边界；系统能在资源被盗取时识别、限流、撤销、取证和恢复。
