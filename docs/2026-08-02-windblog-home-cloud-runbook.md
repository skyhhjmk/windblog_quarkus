# WindBlog 家用云上线与恢复运行手册

这份手册把安全优化方案中的“可运行边界”固定下来。公网入口应为

```text
CDN -> 固定公网边缘节点/WAF -> 主节点反向代理 -> Quarkus
```

仓库提供了可渲染的 [Nginx 边缘模板](../deploy/nginx/windblog-edge.conf.template)：它只代理博客应用，
对 `/q/` 和 `/api/admin/` 使用管理网 allowlist，对受保护下载禁用缓存，并只信任配置的 CDN/边缘代理网段
解析真实客户端 IP。模板中的 `${...}` 必须由部署平台注入，不能原样暴露到公网。

主节点基础服务按需映射到宿主机，默认绑定 `0.0.0.0`，端口为 PostgreSQL 5432、Redis 6379、RabbitMQ 5672、
RabbitMQ 管理端 15672、Elasticsearch 9200 和 Kibana 5601；可用 `*_HOST_PORT` 覆盖宿主机端口。
可用 `WINDBLOG_HOST_BIND_IP` 改为指定宿主机地址（例如 IPv6 的 `::`）。即使绑定到 `0.0.0.0`，这些端口也必须由宿主机防火墙限制到管理网/VPN
或明确的内网来源，不能直接暴露到公网。`docker-compose.yml`
仍使用服务网络、健康检查、CPU/内存上限和 JSON 日志轮转；应用容器由部署平台单独编排，并加入同样的限制。

## 生产启动前

必须提供并保存于部署平台 secret store 的配置至少包括：

- `ADMIN_JWT_SECRET`、`USER_JWT_SECRET`、`SECURITY_EVENT_HASH_SECRET`（事件密钥至少 32 字符）；
- `ADMIN_INIT_PASSWORD`（仅首次初始化使用，随后关闭 `ADMIN_INIT_ENABLED`）；
- `COOKIE_SECURE=true`、`WINDBLOG_SITE_PUBLIC_URL=https://...`；
- 明确的 `CORS_ORIGINS`，且 `CORS_ALLOW_CREDENTIALS=false`；
- 非 guest 的 RabbitMQ 用户密码、PostgreSQL 用户/数据库/密码、Redis/Elasticsearch 密码；
- `SWAGGER_UI_ENABLED=false`，生产启用强制 CSP 和 gRPC mTLS 证书。
- `GRPC_SERVER_CERTIFICATE`、`GRPC_SERVER_KEY`、`GRPC_SERVER_TRUST_STORE`、
  `GRPC_SERVER_TRUST_STORE_PASSWORD` 和 `GRPC_CLIENT_CA_CERTIFICATE`、
  `GRPC_CLIENT_CERTIFICATE`、`GRPC_CLIENT_KEY`；`GRPC_SERVER_CLIENT_AUTH=required`、
  `GRPC_CLIENT_ALLOW_PLAINTEXT_FALLBACK=false`。默认 trust-store 密码和证书缺失时普通生产启动会失败。
- `WIND_BLOG_PROMETHEUS_ENABLED=true`，并通过管理 VPN/内网暴露 `/q/metrics`；
  `WIND_BLOG_MEDIA_DOWNLOAD_MAX_BYTES` 设置单次受保护下载字节上限。
- `WINDBLOG_STORAGE_METRICS_ENABLED=true`，通过 `/q/metrics` 观察媒体盘总容量、可用容量和未分配容量；
  低磁盘告警阈值由监控系统配置，应用不会扫描整棵媒体目录。
- `WIND_BLOG_MEDIA_VIRUS_SCAN_ENABLED=true`、`WIND_BLOG_MEDIA_VIRUS_SCAN_REQUIRED=true`，并配置
  `WIND_BLOG_MEDIA_VIRUS_SCAN_HOST`、`WIND_BLOG_MEDIA_VIRUS_SCAN_PORT`；主节点通过 ClamAV
  `INSTREAM` 扫描后才允许媒体进入变体处理和存储复制。
- 仓库提供可选的 `clamav` Compose security profile；首次部署或更新病毒库时执行
  `docker compose --profile security up -d clamav`，等待 `docker compose ps clamav` 显示 healthy，
  再将主节点的 `WIND_BLOG_MEDIA_VIRUS_SCAN_HOST=127.0.0.1`、`WIND_BLOG_MEDIA_VIRUS_SCAN_PORT=3310`
  指向该服务。Compose 默认使用 `CLAMAV_HOST_BIND_IP=127.0.0.1`，3310 只应开放给主节点；只有
  经防火墙限制的独立扫描节点才允许改为管理网地址，不能暴露公网。
- `POSTGRES_HOST_PORT`、`REDIS_HOST_PORT`、`RABBITMQ_HOST_PORT`、
  `RABBITMQ_MANAGEMENT_HOST_PORT`、`ELASTICSEARCH_HOST_PORT`、`KIBANA_HOST_PORT`
  可用于避开宿主机端口冲突；端口开放不等于允许公网访问。
- `WINDBLOG_CONTENT_ALLOWED_MEDIA_HOSTS` 配置公开文章允许加载的媒体/CDN 域名，多个域名用逗号分隔；
  未列入的外链图片会在公开渲染时移除，避免博客变成任意图片代理。
- `SECURITY_HEADERS_CSP_IMG_SOURCES` 和 `SECURITY_HEADERS_CSP_CONNECT_SOURCES` 只填写明确的 HTTPS
  origin；它们用于独立媒体域、CDN 和 API 域的 CSP allowlist，禁止使用 `*` 或 `https:` 通配来源。
- `SECURITY_HEADERS_CSP_TRUSTED_TYPES_ENABLED=true`；生产 CSP 会强制 Trusted Types，前端通过同源
  `/assets/js/windblog-dom-safety.js` 的默认净化策略处理遗留 DOM HTML sink。
- 密码保护文章只在 `/api/user/post/password/{postId}` 或其表单端点提交一次密码；文章页面、内容、区块和购买
  接口不再接受 `X-Post-Password`，后续请求只能使用短期 `post_access_ticket_{postId}`。API 客户端可通过
  `X-Device-Id` 让票据绑定设备；绑定票据缺少或更换设备标识时会失效。密码页面和受保护内容均返回 `no-store`，
  不能交给 CDN 或共享缓存。
- `WIND_BLOG_MEDIA_DOWNLOAD_MAX_BYTES` 同时限制票据预检的媒体大小和实际流式响应字节数；媒体元数据不准确时也不会
  允许流继续超过该上限。

普通模式缺少上述生产安全条件时必须启动失败。开发和测试 profile 可以使用本地默认值，但不得
将其产生的 token、Cookie 或数据库导出物带入生产。

上线检查：

```powershell
$env:POSTGRES_PASSWORD = 'use-a-secret-store-value'
$env:REDIS_PASSWORD = 'use-a-secret-store-value'
$env:RABBITMQ_DEFAULT_USER = 'windblog'
$env:RABBITMQ_DEFAULT_PASS = 'use-a-secret-store-value'
$env:RABBITMQ_USERNAME = $env:RABBITMQ_DEFAULT_USER
$env:RABBITMQ_PASSWORD = $env:RABBITMQ_DEFAULT_PASS
$env:ELASTIC_PASSWORD = 'use-a-secret-store-value'
$env:KIBANA_SERVICE_ACCOUNT_TOKEN = 'use-a-secret-store-value'
$env:KIBANA_ENCRYPTION_KEY = 'use-a-secret-store-value'
$env:KIBANA_REPORTING_KEY = 'use-a-secret-store-value'
$env:WINDBLOG_BACKUP_GPG_RECIPIENT = 'backup-key-fingerprint-or-email'
.\scripts\validate-windblog-production-env.ps1 -EnvFile .env.production
docker compose config --quiet
docker compose up -d db redis rabbitmq elasticsearch kibana filebeat
docker compose ps
```

RabbitMQ 健康检查会实际验证 Compose 注入的用户和密码；如果已有持久化卷使用旧凭证，容器会显示
`unhealthy`，需要先按维护流程同步凭证或创建对应用户，不能只看管理端口能打开就认为服务可用。

## 打包镜像集成回归

本地验证打包后的 JVM 镜像时，集成容器必须显式连接可访问的 PostgreSQL；测试 profile 关闭
gRPC server，避免把生产证书挂载误当成测试通过条件。该 profile 只用于测试，生产环境仍必须
使用 `prod`、真实数据库和完整 gRPC mTLS 配置：

```powershell
$env:WINDBLOG_IT_DB_PASSWORD = 'use-a-local-test-secret'
# 必须与当前 PostgreSQL 数据卷实际初始化的 POSTGRES_USER/POSTGRES_DB 一致；新卷默认是 windblog。
$env:WINDBLOG_IT_DB_USERNAME = 'windblog'
$env:WINDBLOG_IT_DB_NAME = 'windblog'
.\mvnw.cmd '-Dtest=*IT' '-Dquarkus.http.test-port=18111' `
  '-Dquarkus.test.integration-test-profile=test' `
  "-Dquarkus.datasource.jdbc.url=jdbc:postgresql://host.docker.internal:5432/$env:WINDBLOG_IT_DB_NAME" `
  "-Dquarkus.datasource.username=$env:WINDBLOG_IT_DB_USERNAME" `
  "-Dquarkus.datasource.password=$env:WINDBLOG_IT_DB_PASSWORD" `
  '-Dquarkus.grpc.server.enabled=false' test
```

该回归仍使用打包镜像和容器网络；它不能替代生产证书、CDN、反向代理、边缘回源和恢复演练。

## 外网入口自动化验收

部署到真实 CDN/边缘/反向代理后，在不把 token 或 secret 写入命令行的前提下运行：

```powershell
.\scripts\verify-windblog-cloud.ps1 -PublicBaseUrl 'https://blog.example.com'
```

脚本会检查就绪端点、公开首页是否含旧密码 Cookie、强制 CSP/HSTS、安全响应头、未配置 CORS
来源、生产 Swagger、未授权管理 API、伪造媒体票据和基础 Compose 宿主机端口映射。若健康端点由
内网限制保护，可先在反向代理管理网络运行；只有临时本地 HTTP 检查才允许加
`-AllowHttpForLocalTest`。该脚本不能替代 IPv6、CDN 缓存、下载洪峰、断电恢复和备份恢复演练。

管理端口隔离必须从家庭网络外部或不在 allowlist 内的探测节点执行；将公网解析到边缘节点的地址传给
`-ManagementProbeHost`，脚本会确认 PostgreSQL、Redis、RabbitMQ、RabbitMQ management、Elasticsearch
和 Kibana 端口均不可达。例如：

```powershell
.\scripts\verify-windblog-cloud.ps1 -PublicBaseUrl 'https://blog.example.com' `
    -ManagementProbeHost 'blog.example.com'
```

该探测不能在管理 VPN 内执行，否则得到的是管理网 allowlist 的预期结果，而不是公网隔离证据。

异步任务使用数据库租约和每轮领取上限保护家用云资源：默认 outbox 每轮最多处理 100 个事件、租约
15 分钟，待处理事件上限 100000、已完成/失败事件保留 14 天；邮件每轮最多发送 100 封、租约
5 分钟，待发送邮件上限 100000、已发送/失败邮件保留 90 天；SMTP 连接/读写默认分别为 10/30/30 秒。长时间
媒体处理、RabbitMQ 不可用或 SMTP 故障时，任务会留在可重试状态，不应无限占用调度线程。生产环境可通过
`WINDBLOG_OUTBOX_*` 和 `WINDBLOG_MAIL_*` 调整，但应在压测后再提高上限。

## 备份与恢复

目标是 3-2-1：主机在线数据、同机或外接加密备份、异地/云端加密备份。数据库备份应包含
base backup 和可用时的 WAL；媒体按内容哈希增量同步。备份目录必须不在应用公开静态目录内。

示例流程（实际路径和凭据由部署平台注入）：

```powershell
pg_dump --format=custom --no-owner --file=windblog-$(Get-Date -Format yyyyMMdd-HHmm).dump $env:WINDBLOG_DATABASE_URL
robocopy .\uploads $env:WINDBLOG_MEDIA_BACKUP uploads /E /Z /R:2 /W:5
```

仓库还提供 [backup-windblog.ps1](../scripts/backup-windblog.ps1)。它要求通过 `PGPASSWORD` 和
`WINDBLOG_BACKUP_GPG_RECIPIENT` 注入数据库密码与 GPG 公钥，数据库 dump 和媒体副本都会先加密再写入备份目录，
并生成带 SHA-256 清单；脚本只在明确校验过的备份根目录内执行保留清理，不提供明文备份绕过。
该脚本不把“WAL 已归档”当作事实；生产仍需单独配置 PostgreSQL WAL 归档并完成恢复演练。

如果备份执行节点没有宿主机 `pg_dump/pg_restore`，而 PostgreSQL 客户端位于 Compose 容器内，
可使用脚本的容器模式。`PGUSER`、`PGDATABASE` 必须先按现有数据卷实际初始化的角色和数据库设置，
不能盲目使用示例中的 `windblog`：

```powershell
$env:PGUSER = 'actual-postgres-user'
$env:PGDATABASE = 'actual-postgres-database'
$env:PGPASSWORD = 'use-a-secret-store-value'
$env:WINDBLOG_BACKUP_GPG_RECIPIENT = 'backup-key-fingerprint-or-email'
.\scripts\backup-windblog.ps1 -BackupRoot 'D:\backups\windblog' -PgDumpContainer postgresdb
.\scripts\validate-windblog-backup.ps1 -BackupPath 'D:\backups\windblog\<timestamp>' `
    -RestoreRoot 'D:\restore\windblog-<timestamp>' -PgRestoreContainer postgresdb
```

容器模式只在容器内生成临时归档并复制到加密备份目录；验证脚本只执行解密、媒体恢复和
`pg_restore --list`，不会向任何 PostgreSQL 数据库执行恢复写入。

可用 [validate-windblog-backup.ps1](../scripts/validate-windblog-backup.ps1) 在隔离目录做恢复验证：
它校验 manifest 和加密数据库归档、解密媒体、运行 `pg_restore --list`，并明确不会覆盖任何 PostgreSQL 数据库。
真正的数据库恢复仍需在隔离实例中由运维人员执行 `pg_restore`、迁移校验和业务 HTTP 验收。

恢复演练至少每季度一次，并在隔离 PostgreSQL 和隔离媒体目录中验证：文章正文和发布版本、用户
状态、购买记录、媒体引用、票据撤销状态、密钥轮换后的配置以及边缘撤销清单。恢复成功的证据
应记录备份时间、数据库变更集、媒体哈希数量和公开文章/登录/受保护下载的 HTTP 结果。

## 故障降级与恢复

- Elasticsearch 不可用时，搜索使用 PostgreSQL `tsvector + GIN` 分页 fallback；公开文章读取不依赖 ES。
- RabbitMQ 不可用时，文章索引事件写入 PostgreSQL outbox，使用租约、幂等事件键和指数退避恢复。
- Redis 不可用时，只允许本地保护性限流，不能把它当作跨节点撤销或授权状态的真源。
- CDN/边缘故障时，已缓存的公开内容可继续服务；登录、购买、原图和受保护附件必须失败或回源
  到受控主节点，不能通过备用公开 URL 绕过边缘。
- 主机重启后先确认 PostgreSQL、Redis、RabbitMQ、ES 健康，再确认 outbox、邮件租约和媒体处理
  状态没有重复扣费或重复发信。

运行看板至少保留：公开读取错误率和延迟、搜索降级次数、outbox pending/in-flight/failed、邮件
租约超时、下载授权拒绝和异常行为事件、媒体磁盘水位、边缘心跳/游标差距、备份年龄。应用
Micrometer 指标由 `/q/metrics` 提供，受保护下载证据可通过
`GET /api/admin/security/media-download-events` 分页查询。指标和日志
不得记录原始密码、JWT、API key、完整 Cookie、签名 URL 或完整 IP。

真实家用网络验收仍需人工执行：反向代理和 IPv6、证书续期、外网访问控制、磁盘满、上游 DNS
变化、下载洪峰下缩略图/原图降级、断电恢复以及备份恢复。编译和单元测试不能替代这些验收。
