# windblog_quarkus

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## 生产部署 Quick Start（Docker Compose）

下面的流程适用于使用仓库已经发布的 native 镜像启动 WindBlog。服务器不需要上传
`admin-flutter/`、`codex-creator/` 源码、Maven `target/` 或 Flutter `build/`；Compose
会拉取 WindBlog 和 Codex Creator 镜像，Elasticsearch 的 IK 镜像则在服务器本地构建。

### 1. 服务器要求

服务器需要 Linux、Docker Engine + Docker Compose Plugin（或兼容的 Podman Compose）、
`openssl`、Java Runtime 中的 `keytool`，并能访问 Docker Hub 和 GHCR。公网只应开放
反向代理的 `80/443`；Compose 默认把 WindBlog HTTP 绑定到本机 `127.0.0.1:8080`，
数据库、Redis、RabbitMQ、Elasticsearch 和 Kibana 端口必须由防火墙限制在管理网，不能直接
暴露到公网。

如果 `CODEX_CREATOR_IMAGE` 指向私有 GHCR 镜像，先在服务器登录 GHCR；令牌只需要
`read:packages`，不要写进 `.env`：

```bash
docker login ghcr.io
```

### 2. 上传文件

建议将以下文件上传到服务器的 `/opt/windblog/`，并始终从这个目录执行命令：

```text
/opt/windblog/
├── .env                              # 必须，生产配置和密钥，权限 600
├── .env.example                       # 配置模板，不含真实密钥
├── admin.sh                          # Compose 管理脚本
├── docker-compose.yml                # 主服务编排
├── filebeat.docker.yml               # Filebeat 配置
├── docker/
│   └── elasticsearch/Dockerfile      # 构建 IK 镜像
├── src/main/resources/elasticsearch/ # 建议上传，用于 ES/Kibana 初始化
│   ├── create-kibana-token.sh
│   ├── setup.sh
│   ├── ilm-policy*.json
│   ├── index-template*.json
│   └── kibana-*.json
├── deploy/nginx/windblog-edge.conf.template # 可选，反向代理模板
├── certs/                             # 首次可不上传，由 admin.sh prepare 生成
│   └── ca/                            # 已有环境必须整体保留
└── 持久化目录/                         # 新服务器可由脚本创建，不要放进代码压缩包
    ├── postgres-data/
    ├── redis-data/
    ├── rabbitmq_data/
    ├── elasticsearch-data/
    ├── kibana-data/
    ├── clamav-data/
    ├── windblog-logs/
    ├── uploads/
    ├── wesp-blocks/
    ├── wesp-config/
    ├── codex-creator-data/
    └── codex-creator-logs/
```

`.env` 应从 `.env.example` 复制后在服务器生成或填写，不能上传真实的 `.env.example`
占位值作为生产配置。不要上传 `.git/`、日志、数据库目录、上传文件、私钥或本机的
`codex-auth/`；已有实例迁移时，`postgres-data/`、`uploads/`、`certs/` 和其他持久化目录
必须单独备份并原样迁移。

数据库结构不通过额外的 PostgreSQL init SQL 文件维护。WindBlog 的业务表由
`src/main/resources/db/migration/` 下的 Liquibase changeset 在 WindBlog 启动时迁移；
Codex Creator 的独立数据库表由
`codex-creator/src/main/resources/db/changelog/` 下的 Liquibase changeset 在
Codex Creator 启动时迁移。当前 `docker-compose.yml` 的 `codex-creator-db-init` 服务会在
PostgreSQL healthy 后幂等地创建 `codex_creator` 数据库并启用 `vector` 扩展，然后由
Codex Creator 自己执行 Liquibase 迁移；因此不再需要额外上传或手工执行数据库 init SQL。

可用下面的命令从本地仓库制作不包含运行数据和密钥的上传包：

```bash
tar --exclude='.git' \
    --exclude='.env' \
    --exclude='target' \
    --exclude='admin-flutter/build' \
    --exclude='admin-flutter/.dart_tool' \
    --exclude='codex-creator/target' \
    --exclude='codex-auth' \
    --exclude='postgres-data' --exclude='redis-data' \
    --exclude='rabbitmq_data' --exclude='elasticsearch-data' \
    --exclude='kibana-data' --exclude='clamav-data' \
    --exclude='windblog-logs' --exclude='uploads' \
    --exclude='wesp-blocks' --exclude='wesp-config' \
    --exclude='codex-creator-data' --exclude='codex-creator-logs' \
    -czf windblog-compose.tar.gz \
    README.md AGENTS.md .env.example admin.sh docker-compose.yml filebeat.docker.yml \
    docker/elasticsearch src/main/resources/elasticsearch deploy/nginx
scp windblog-compose.tar.gz user@your-server:/opt/
ssh user@your-server 'mkdir -p /opt/windblog && tar -xzf /opt/windblog-compose.tar.gz -C /opt/windblog'
```

### 3. 首次启动

进入服务器目录，生成生产配置并准备挂载目录和 gRPC mTLS 证书：

```bash
cd /opt/windblog
cp .env.example .env
chmod 600 .env
./admin.sh env-generate
./admin.sh prepare
```

然后编辑 `.env`，至少修改这些值：

```dotenv
WINDBLOG_SITE_PUBLIC_URL=https://blog.example.com
CORS_ORIGINS=https://blog.example.com
WINDBLOG_WESP_AUTH_TOKEN=<与其他节点一致的强随机 token>
```

`env-generate` 会补全大多数随机密钥；`WINDBLOG_SITE_PUBLIC_URL` 和 `CORS_ORIGINS`
必须改成真实的 HTTPS 域名。生产保持 `QUARKUS_PROFILE=prod`、`COOKIE_SECURE=true`、
`SECURITY_FAIL_ON_DEFAULT_SECRETS_IN_PROD=true`，不要用关闭安全检查的方式绕过配置错误。
如果启用 AI 文章生成，再按需配置 `CODEX_APP_SERVER_ENABLED=true` 以及
`OPENAI_API_KEY` 或 `CODEX_ACCESS_TOKEN`；凭据不要提交 Git。

Kibana 服务账户令牌需要先让 Elasticsearch 启动一次。由于完整 Compose 配置要求该令牌，
首次启动依次执行：

```bash
# 仅用临时占位值启动基础依赖；它不会写入 .env
KIBANA_SERVICE_ACCOUNT_TOKEN=bootstrap-placeholder \
  docker compose --env-file .env --profile security \
  up -d db redis rabbitmq elasticsearch clamav

# 等 Elasticsearch healthy 后创建真实令牌，并自动回写 .env
set -a
. ./.env
set +a
ES_HOST=127.0.0.1 \
ES_PORT="${ELASTICSEARCH_HOST_PORT:-9200}" \
ES_USERNAME=elastic \
ES_PASSWORD="$ELASTIC_PASSWORD" \
  ./src/main/resources/elasticsearch/create-kibana-token.sh

./admin.sh env-check
./admin.sh start

# admin.sh start 会启动 WindBlog 及其依赖；以下命令再启动 Kibana 和 Filebeat
docker compose --env-file .env --profile security up -d --no-build kibana filebeat
docker compose --env-file .env ps
./admin.sh health
```

如果只需要博客和后台，不使用日志可视化，可以不启动 `kibana` 和 `filebeat`；
Elasticsearch 仍由 WindBlog 依赖链启动。首次部署完成后，可按需初始化 Elasticsearch
索引模板和 Kibana 对象：

```bash
set -a
. ./.env
set +a
ES_HOST=127.0.0.1 \
ES_PORT="${ELASTICSEARCH_HOST_PORT:-9200}" \
ES_USERNAME=elastic \
ES_PASSWORD="$ELASTIC_PASSWORD" \
  ./src/main/resources/elasticsearch/setup.sh
```

服务就绪后，访问 `https://blog.example.com`。管理 API 和 `/q/health/ready` 应只通过
管理网/VPN 或反向代理 allowlist 访问；反向代理可使用
[`deploy/nginx/windblog-edge.conf.template`](deploy/nginx/windblog-edge.conf.template)，
但模板中的域名、证书路径、上游地址和管理网段必须由部署环境填写。

### 4. 目录和服务关系

所有相对路径都相对于 `/opt/windblog`（即 Compose 项目目录）。数据库和应用数据通过
`WINDBLOG_DATA_DIR` 挂载，gRPC 证书通过 `WINDBLOG_CERT_DIR` 挂载：

```text
docker compose 项目
├── windblog          # 主博客、后台 API，HTTP 8080，gRPC 9000
├── codex-creator     # AI 编排服务，容器网络内 8681
├── db                # PostgreSQL + pgvector
├── redis             # 缓存和限流
├── rabbitmq          # 异步任务队列
├── elasticsearch     # 日志和文章搜索，含 IK 插件
├── kibana            # 可选的日志界面
├── filebeat          # 可选的日志采集
└── clamav            # security profile，媒体病毒扫描
```

`postgres-data/`、`uploads/`、`windblog-logs/`、`wesp-*`、`codex-creator-*` 和证书目录
都是运行数据。升级镜像或重新上传代码时只替换代码/编排文件，不能删除这些目录，也不要执行
带 `-v` 的 `docker compose down`，否则会删除数据库和其他服务数据。

### 5. 日常操作和更新

```bash
cd /opt/windblog
./admin.sh status                 # 查看容器状态
./admin.sh logs                   # 跟踪 WindBlog 日志
./admin.sh health                 # 检查 readiness
./admin.sh config                 # 校验 Compose 配置
./admin.sh restart                # 拉取最新 WindBlog 镜像并重启
```

更新时先备份 `.env`、数据库和 `uploads/`，再上传新的 `docker-compose.yml`、
`admin.sh`、`filebeat.docker.yml`、`docker/` 等代码文件；保留服务器现有 `.env`、`certs/`
和所有持久化目录。修改镜像 tag 后，先执行 `./admin.sh config`，确认无误再执行
`./admin.sh restart`，最后用 `./admin.sh status`、`./admin.sh health` 和公开域名请求验证。

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./scripts/run-local-dev.sh
```

`run-local-dev.sh` 会读取根目录 `.env`（也可通过 `WINDBLOG_ENV_FILE` 指定文件），并将 Compose 使用的 `POSTGRES_*`、`REDIS_*` 映射为应用使用的 `DB_*`、`REDIS_URL`。直接运行 Maven 时，需要先手动 `source .env`；Compose 则会自动读取同一份 `.env`。

> **_NOTE:_**  Quarkus now ships with a Dev UI, which is available in dev mode only at <http://localhost:8080/q/dev/>.

## Packaging and running the application

The application can be packaged using:

```shell script
./mvnw package
```

It produces the `quarkus-run.jar` file in the `target/quarkus-app/` directory.
Be aware that it’s not an _über-jar_ as the dependencies are copied into the `target/quarkus-app/lib/` directory.

The application is now runnable using `java -jar target/quarkus-app/quarkus-run.jar`.

If you want to build an _über-jar_, execute the following command:

```shell script
./mvnw package -Dquarkus.package.jar.type=uber-jar
```

The application, packaged as an _über-jar_, is now runnable using `java -jar target/*-runner.jar`.

## Creating a native executable

You can create a native executable using:

```shell script
./mvnw package -Dnative
```

Or, if you don't have GraalVM installed, you can run the native executable build in a container using:

```shell script
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

You can then execute your native executable with: `./target/windblog_quarkus-1.0-SNAPSHOT-runner`

## Native container image publishing

The GitHub Actions native image workflow publishes the private GHCR repository
`ghcr.io/skyhhjmk/windblog_quarkus`. It uses the repository `GITHUB_TOKEN`, so
no registry username/password variables are required. Pull requests and other
branches build images without pushing; `main`, `master`, and `v1.2.3` tags
push the release images.

Jenkins uses the same `CONTAINER_REGISTRY`, `CONTAINER_IMAGE_GROUP`, and
`CONTAINER_IMAGE_NAME` environment variables. Set `REGISTRY_CREDENTIALS_ID`
to the Jenkins username/password credential used for registry login. Jenkins
also skips the push when the registry or credential ID is absent.

The GitHub Actions release job builds only native images:

- `Dockerfile.native`: the WindBlog primary image, published as
  `<version>-native`, `sha-<commit>-native`, and `latest-native`
- `Dockerfile.native-micro`: the edge image, published as `<version>`,
  `sha-<commit>`, and `latest`

The same release images are also pushed to Docker Hub. Configure the repository
secrets `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN`; the token should be a Docker
Hub access token with permission to push to the `windblog_quarkus` repository.

The workflow does not build or push a JVM image. The edge image is compiled
with the `edge` profile and the primary image with the `prod` profile; they
must not share a native runner because Quarkus fixes messaging topology at
native build time.

The Jenkinsfile is a separate pipeline. It is not used by this GHCR workflow;
configure its registry credentials independently if Jenkins is retained.

Native edge variants are compiled with `-Dquarkus.profile=edge`. Native images
must use the same profile at build time and runtime because messaging channel
topology is fixed during native compilation. Setting only
`QUARKUS_PROFILE=edge` when starting an image built with `prod` is not enough.

The edge deployment package defaults to
`docker.io/hhjmk/windblog_quarkus:latest`. Override the default repository with
`EDGE_IMAGE_REPOSITORY`, or pass `image` and `variant` to
`GET /api/admin/edge-nodes/{nodeId}/deployment-zip`. The `image` parameter is
the complete image reference written to `.env`, including its tag or digest.
If the Docker Hub repository is private, authenticate the deployment host with
`docker login` before running `docker compose pull`; keep that credential outside
`.env`.

If you want to learn more about building native executables, please consult <https://quarkus.io/guides/maven-tooling>.

## Related Guides

- Messaging - RabbitMQ Connector ([guide](https://quarkus.io/guides/rabbitmq)): Connect to RabbitMQ with Reactive
  Messaging
- Hibernate ORM with Panache ([guide](https://quarkus.io/guides/hibernate-orm-panache)): Simplify your persistence code
  for Hibernate ORM via the active record or the repository pattern
- SmallRye JWT ([guide](https://quarkus.io/guides/security-jwt)): Secure your applications with JSON Web Token
- Liquibase ([guide](https://quarkus.io/guides/liquibase)): Handle your database schema migrations with Liquibase
- Redis Client ([guide](https://quarkus.io/guides/redis)): Connect to Redis in either imperative or reactive style
- Redis Cache ([guide](https://quarkus.io/guides/cache-redis-reference)): Use Redis as the caching backend
- SmallRye Context Propagation ([guide](https://quarkus.io/guides/context-propagation)): Propagate contexts between
  managed threads in reactive applications
- SmallRye JWT Build ([guide](https://quarkus.io/guides/security-jwt-build)): Create JSON Web Token with SmallRye JWT
  Build API
- JDBC Driver - PostgreSQL ([guide](https://quarkus.io/guides/datasource)): Connect to the PostgreSQL database via JDBC
- Logging JSON ([guide](https://quarkus.io/guides/logging)): JSON structured logging for Elasticsearch integration

## Provided Code

### Hibernate ORM

Create your first JPA entity

[Related guide section...](https://quarkus.io/guides/hibernate-orm)

[Related Hibernate with Panache section...](https://quarkus.io/guides/hibernate-orm-panache)

### 1. 博客基础功能

#### 1.1 文章管理
- **文章发布**: 支持 Markdown 和 HTML 格式
- **文章状态**: 草稿、已发布、归档三种状态
- **可见性控制**: 公开、私密、密码保护
- **特色文章**: 可标记文章为特色推荐
- **评论控制**: 单篇文章可独立控制评论开关
- **SEO优化**: 自定义标题、描述、关键词
- **AI摘要**: 自动生成文章摘要
- **浏览统计**: 文章阅读量追踪

#### 1.2 分类与标签
- **多级分类**: 支持无限层级分类体系
- **标签系统**: 灵活的文章标签管理
- **分类级别**: 可配置分类层级显示

#### 1.3 用户系统
- **前台用户**: 注册、登录、个人资料管理
- **邮箱验证**: 用户激活机制
- **OAuth登录**: 支持第三方登录(Github、Google、微信等)
- **用户等级**: 积分和等级系统
- **评论权限**: 已验证用户才能评论

### 2. 内容展示功能

#### 2.1 页面渲染
- **PJAX无刷新**: 局部页面更新，提升用户体验
- **AMP支持**: Google AMP标准页面
- **首屏优化**: Instant First Paint 技术
- **响应式设计**: 移动端友好
- **骨架屏**: 加载时显示内容骨架

#### 2.2 搜索功能
- **全文检索**: ElasticSearch 驱动
- **多维度搜索**: 按标题、内容、标签等搜索
- **搜索排序**: 时间、相关性等多种排序方式
- **搜索过滤**: 按分类、日期等条件过滤

#### 2.3 导航与面包屑
- **智能面包屑**: 自动生成当前位置导航
- **菜单管理**: 可配置的导航菜单
- **侧边栏**: 动态侧边栏内容

### 3. AI增强功能

#### 3.1 AI摘要生成
- **多提供商支持**: OpenAI、Claude、Gemini、智谱等
- **队列处理**: RabbitMQ 异步任务处理
- **故障转移**: 多提供者自动切换
- **优先级调度**: 支持高、中、低优先级任务

#### 3.2 AI内容审核
- **评论审核**: 自动审核用户评论内容
- **链接审核**: 友情链接安全性检查
- **敏感词过滤**: 内容安全检测

#### 3.3 AI翻译服务
- **百度翻译集成**: 支持中文翻译
- **多语言支持**: 国际化内容处理

### 4. 安全防护体系

#### 4.1 访问控制
- **CSRF防护**: 全局CSRF令牌验证
- **IP黑白名单**: IP访问控制
- **权限验证**: 细粒度权限控制
- **会话管理**: 安全的用户会话处理

#### 4.2 安全头设置
- **X-Frame-Options**: 防止点击劫持
- **Content-Security-Policy**: 内容安全策略
- **Strict-Transport-Security**: 强制HTTPS
- **X-XSS-Protection**: XSS防护
- **Referrer-Policy**: Referer控制

#### 4.3 文件上传安全
- **文件类型限制**: 严格的文件格式控制
- **大小限制**: 上传文件大小控制
- **恶意文件检测**: 安全扫描机制

### 5. 性能优化

#### 5.1 缓存机制

热点数据缓存

#### 5.2 静态化
- **静态页面生成**: 全站静态化支持
- **CDN集成**: 边缘节点同步
- **资源压缩**: CSS/JS文件压缩合并

#### 5.3 数据库优化
- **查询优化**: 智能查询构建
- **索引优化**: 自动索引管理
- **连接池**: 数据库连接复用

### 6. 友情链接系统

#### 6.1 链接管理
- **链接收录**: 自助提交和管理员审核
- **分类管理**: 链接分类组织
- **权重系统**: 链接重要性评级
- **状态监控**: 链接有效性检查

#### 6.2 外链检测
- **安全性检查**: 检测恶意链接
- **可用性监控**: 链接存活状态
- **自动审核**: AI驱动的内容审核
- **定时检查**: 定期链接健康度检查

### 7. 媒体管理

#### 7.1 媒体库
- **文件上传**: 支持多种媒体格式
- **图片处理**: 自动缩放和格式转换
- **存储管理**: 本地和云存储支持
- **元数据管理**: 文件信息管理

#### 7.2 广告系统
- **位置管理**: 多种广告位支持
- **投放控制**: 精准投放策略
- **效果统计**: 广告效果追踪
- **Google Adsense**: 原生支持

### 8. 系统管理

#### 8.1 命令行工具

- 系统检查
- 配置管理
- 缓存清理
- 数据导入
- 性能分析

#### 8.2 后台进程
- **监控进程**: 系统性能监控
- **队列工作者**: 异步任务处理
- **定时任务**: 计划任务执行
- **导入进程**: 数据迁移处理

#### 8.3 部署方式

以微服务思想为主，可以使用消息队列/http/grpc直接调用外部服务，也可以以all in one模式全部使用内置实现

---

## 项目开发进度 (Implementation Status)

本项目目前已完成核心基础设施及部分关键功能的开发。以下按实现顺序排列：

1.  **AI 摘要生成后端基础设施** (功能 1.1 & 3.1)
    - 核心思路：通过 `AiManager` 实现多供应商（OpenAI, Gemini 等）的 Failover 机制。
    - 技术细节：集成 **RabbitMQ** 异步处理耗时任务，使用 `AiTaskProducer/Consumer` 模式确保系统响应速度。

2.  **文章访问控制：密码保护** (功能 1.1)
    - 核心思路：在数据库层面扩展访问权限，支持多种可见性状态。
    - 实现：完成了 Liquibase 迁移、后端 DTO 更新及 `PostController` 中的权限拦截逻辑。

3.  **友情链接自动化监控系统** (功能 6.1 & 6.2)
    - 核心思路：引入自动化巡检机制替代人工维护。
    - 实现：利用 **Quarkus Scheduler** 实现定时健康检查，记录外部链接状态及反链存活情况。

4.  **SEO 优化体系** (功能 1.1)
    - 核心思路：增强文章在搜索引擎中的元数据控制。
    - 实现：为 `Post` 模型增加了自定义 `seoTitle`、`seoKeywords` 与 `seoDescription`，并同步更新了管理接口。

5.  **Admin Flutter 后台重构与增强**
    - 核心思路：将单文件架构迁移为**按页面/模块存储**的生产级架构。
    - 优化点：
        - 引入 **NavigationRail** 实现更专业的侧边导航体验。
        - 适配新增的 AI 摘要手动触发、文章密码设置及 SEO 配置功能。
        - 深度优化了富文本编辑器（Quill）与 Markdown 编辑器的切换逻辑。
        - 完成了全面的 Dart 代码审计与 Lint 修复。

6. **友情链接标签与分类系统** (功能 6.1)
    - 核心思路：增强友情链接的组织和管理能力，支持多维度分类。
    - 技术细节：
        - 新增 `LinkTag` 标签实体，支持多语言名称和描述（JSONB 存储）。
        - 新增 `LinkType` 枚举类型，支持多种链接类型（友情链接、网盘资源、文章链接、工具链接等）。
        - 实现 `LinkTagRelation` 关联表，支持多对多关系。
        - 完成相关数据库迁移脚本（029-032）。

7. **上传角色权限系统** (功能 7.1 & 8.1)
    - 核心思路：细化文件上传权限控制，支持按角色分配上传配额和权限。
    - 实现：
        - 新增 `UploadRole` 实体，定义不同用户组的上传限制（文件大小、类型、日限额等）。
        - 扩展 `Media` 模型，添加上传者信息和文件元数据。
        - 后端实现权限校验逻辑，确保上传操作符合角色配置。

8. **Elasticsearch 日志管理与搜索优化** (基础设施)
    - 核心思路：集中式日志管理和生命周期管理，优化存储成本和查询性能。
    - 实现：
        - 集成 Elasticsearch 9.2 和 Kibana 9.2
        - 实现三级存储生命周期（热温冷）
        - 自动日志推送和结构化日志格式
        - 预配置 Kibana 仪表板和视图

9. **Elasticsearch 文章全文搜索** (功能 2.2)
    - 核心思路：利用 Elasticsearch 强大的全文搜索能力提升文章搜索体验。
    - 实现：
        - 自动索引已发布文章到 Elasticsearch
        - 支持中文分词（SmartChinese Analyzer）
        - 多字段搜索（标题、内容、摘要、SEO 信息、标签）
        - 搜索结果相关性评分和排序
        - 自动同步机制（文章发布/更新/删除时自动同步索引）
        - 回退机制（Elasticsearch 不可用时自动切换到数据库搜索）

## 核心设计思路 (Core Ideas)

- **模块化构建**：后端采用 Service 模式解耦 AI 与监控逻辑；前端通过多页面分离提升开发效率。
- **性能优先**：通过消息队列处理重型任务，通过 PJAX/缓存技术优化前台渲染。
- **安全性与透明度**：完善的权限校验与监控日志记录，确保系统运行状态可追溯。
- **现代化审美**：后台管理系统遵循 Material 3 设计规范，提供极简、高效的操作体验。

---

## Elasticsearch 日志管理 (Elasticsearch Log Management)

### 快速开始 (Quick Start)

#### 1. 配置环境变量

生产部署建议使用管理脚本生成并检查环境变量：

```bash
./admin.sh env-generate
./admin.sh env-check
```

生成的生产配置默认使用 `QUARKUS_PROFILE=prod`、`COOKIE_SECURE=true` 和 gRPC mTLS；请先把 `WINDBLOG_SITE_PUBLIC_URL` 改为实际的 `https://` 地址。

首次启动不会再从环境变量创建管理员。服务启动后，Admin Flutter 使用服务器地址尝试登录；如果服务器尚未安装，登录接口会返回安装引导，客户端进入“安装初始化”页面。完成超级管理员、站点标题、描述、关键词和本站链接等信息后，系统在一个事务中写入管理员与站点配置，之后再使用新账号登录。

#### 2. 启动服务

```bash
# 构建本地 Elasticsearch IK 镜像并启动生产服务
./admin.sh build
./admin.sh start

# 等待 Elasticsearch 就绪后，运行初始化脚本
# Windows PowerShell:
.\src\main\resources\elasticsearch\setup.ps1

# Linux/Mac:
./src/main/resources/elasticsearch/setup.sh
```

**初始化脚本参数**：
- `ES_USERNAME`: Elasticsearch 用户名（默认：elastic）
- `ES_PASSWORD`: Elasticsearch 密码（默认：elastic123456）
- `ELASTICSEARCH_USE_HTTPS`: 是否使用 HTTPS（默认：false）

#### 3. 访问服务

- **Elasticsearch**: http://localhost:9200 (需要认证)
- **Kibana**: http://localhost:5601
  - 用户名：`elastic`
  - 密码：`.env` 文件中配置的 `ELASTIC_PASSWORD`

#### 3. 查看日志

启动应用后，日志会自动推送到 Elasticsearch，可在 Kibana 中查看：

1. 访问 Kibana: http://localhost:5601
2. 进入 "Discover" 页面
3. 选择 "windblog-logs-*" 数据视图
4. 使用预配置的视图和仪表板：
   - **所有日志**: 查看所有应用日志
   - **错误日志**: 快速定位 ERROR 级别日志
   - **警告日志**: 查看 WARN 级别日志
   - **日志分析仪表板**: 综合分析仪表板

### 日志生命周期管理 (ILM)

系统自动管理日志的生命周期，分为三个阶段：

#### 热存储 (Hot Tier) - 0-7 天
- **压缩方式**: LZ4
- **优先级**: 高 (100)
- **特点**: 快速索引和查询
- **适用场景**: 最新日志，频繁查询

#### 温存储 (Warm Tier) - 7-30 天
- **压缩方式**: ZSTD
- **优先级**: 中 (50)
- **特点**: 平衡存储和查询性能
- **操作**: 强制合并为 1 个段，减少分片

#### 冷存储 (Cold Tier) - 30 天以上
- **压缩方式**: best_compression (DEFLATE)
- **优先级**: 低 (0)
- **特点**: 最高压缩率，冻结索引
- **适用场景**: 归档日志，偶尔查询

#### 自动删除 - 90 天以上
- 超过 90 天的日志自动删除

### 配置说明

#### Docker Compose 配置
```yaml
elasticsearch:
  image: docker.elastic.co/elasticsearch/elasticsearch:9.3.2
  ports:
    - "9200:9200"
  environment:
    - discovery.type=single-node
    - xpack.security.enabled=false

kibana:
  image: docker.elastic.co/kibana/kibana:9.3.2
  ports:
    - "5601:5601"
  depends_on:
    - elasticsearch
```

#### 应用日志配置
```properties
# 基础配置
quarkus.log.handler.elasticsearch.enabled=true
quarkus.log.handler.elasticsearch.index=windblog-logs
quarkus.log.handler.elasticsearch.hosts=${ELASTICSEARCH_HOSTS:http://elasticsearch:9200}
quarkus.log.handler.elasticsearch.format=json

# 认证配置（从环境变量读取）
quarkus.log.handler.elasticsearch.username=${ELASTICSEARCH_USERNAME:}
quarkus.log.handler.elasticsearch.password=${ELASTICSEARCH_PASSWORD:}

# SSL 配置（生产环境启用）
quarkus.log.handler.elasticsearch.ssl-trust-all=${ELASTICSEARCH_SSL_TRUST_ALL:false}
```

### 常用 Kibana 查询

```kibana
# 查看所有日志
*

# 查看错误日志
level: ERROR

# 查看特定类的日志
class: "com.biliwind.blog.controller.*"

# 查看最近 1 小时的日志
@timestamp >= now-1h

# 查看特定消息的日志
message: "exception" OR message: "error"
```

### 安全配置

#### X-Pack 安全特性
系统已启用 Elasticsearch X-Pack 安全功能：
- **认证**: 基于用户名/密码的身份验证
- **授权**: 基于角色的访问控制（RBAC）
- **加密**: 节点间通信加密（生产环境建议启用 TLS）
- **审计**: 安全事件审计日志

#### 密钥管理
所有敏感配置通过环境变量管理：
- `ELASTIC_PASSWORD`: Elasticsearch 超级用户密码
- `KIBANA_ENCRYPTION_KEY`: Kibana 加密密钥（32 字节）
- `KIBANA_REPORTING_KEY`: Kibana 报表加密密钥（32 字节）

**生产环境建议**：
```bash
# 生成强密码
openssl rand -base64 32

# 生成加密密钥
openssl rand -hex 32
```

### 故障排查

#### Elasticsearch 启动失败
```bash
# 查看日志
docker logs elasticsearch

# 检查健康状态（需要认证）
curl -u elastic:<password> http://localhost:9200/_cluster/health
```

#### 日志未推送
1. 检查应用配置中的 Elasticsearch 地址
2. 确认网络连接正常
3. 查看应用日志是否有推送错误

#### Kibana 无法连接 Elasticsearch
1. 确认 Elasticsearch 已启动并健康
2. 检查 Kibana 配置中的连接地址
3. 重启 Kibana 服务

### 资源清理

```bash
# 停止所有服务
docker-compose down

# 删除数据卷（谨慎操作，会删除所有数据）
docker-compose down -v
```

**注意**: 删除数据卷会永久删除所有日志数据，请谨慎操作。

---

## Elasticsearch 文章搜索 (Elasticsearch Post Search)

### 功能特性

- **自动索引**: 文章发布时自动索引到 Elasticsearch
- **全文搜索**: 支持中文分词，多字段搜索
- **智能排序**: 按相关性评分和发布时间排序
- **自动同步**: 文章更新/删除时自动同步索引
- **回退机制**: Elasticsearch 不可用时自动切换到数据库搜索

### 搜索范围

文章搜索支持以下字段：

- **标题** (权重最高，3 倍)
- **SEO 标题** (2 倍权重)
- **文章内容**
- **摘要**
- **标签**
- **SEO 描述**

### 索引配置

#### 文章索引模板

- **索引名称**: `windblog-posts-*`
- **别名**: `windblog-posts`
- **分词器**: SmartChinese Analyzer (中文分词)
- **刷新间隔**: 5 秒

#### 字段映射

```json
{
  "title": {
    "type": "text",
    "analyzer": "chinese_analyzer",
    "fields": { "keyword": { "type": "keyword" } }
  },
  "content": { "type": "text", "analyzer": "chinese_analyzer" },
  "summary": { "type": "text", "analyzer": "chinese_analyzer" },
  "tags": { "type": "keyword" },
  "status": { "type": "keyword" },
  "publishedAt": { "type": "date" }
}
```

### 管理 API

#### 1. 检查 Elasticsearch 健康状态

```bash
GET /api/admin/elasticsearch/health
```

响应示例：
```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "ilmPolicyExists": true,
    "indexTemplateExists": true
  }
}
```

#### 2. 获取 ILM 策略信息

```bash
GET /api/admin/elasticsearch/ilm-policy
```

#### 3. 获取索引模板信息

```bash
GET /api/admin/elasticsearch/index-template
```

#### 4. 搜索文章（管理后台）

```bash
GET /api/admin/elasticsearch/posts/search?q=关键词&page=1&size=20
```

#### 5. 手动索引文章

```bash
POST /api/admin/elasticsearch/posts/{postId}/index
```

#### 6. 删除文章索引

```bash
DELETE /api/admin/elasticsearch/posts/{postId}/index
```

### 前台搜索

前台搜索页面自动使用 Elasticsearch：

```
/search?q=搜索关键词&use-es=true
```

参数说明：
- `q`: 搜索关键词
- `use-es`: 是否使用 Elasticsearch（默认 true）
- `type`: 搜索类型（all/post/tag/category）
- `sort`: 排序方式（默认/最新/热门）
- `date`: 日期范围（全部/7 天/30 天/365 天）
- `page`: 页码

### 自动同步机制

文章在以下情况会自动同步到 Elasticsearch：

1. **文章发布**: 状态变为 `PUBLISHED` 时自动索引
2. **文章更新**: 已发布文章更新时重新索引
3. **文章删除**: 删除时从索引中移除
4. **状态变更**: 从已发布变为草稿时移除索引

### 搜索性能优化

- **异步索引**: 文章同步使用异步线程池，不阻塞主流程
- **批量操作**: 支持批量索引操作（待实现）
- **缓存机制**: 搜索结果可以结合 Redis 缓存（待实现）

### 故障处理

#### Elasticsearch 不可用

系统会自动回退到数据库搜索，确保搜索功能始终可用：

```java
try {
    // Elasticsearch 搜索
} catch (Exception e) {
    log.error("Elasticsearch 搜索失败，回退到数据库搜索", e);
    // 数据库搜索
}
```

#### 索引失败处理

- 记录错误日志
- 不影响文章发布流程
- 可以通过管理 API 手动重新索引

### 监控和日志

应用会自动记录以下日志：

- 索引初始化过程
- 文章同步状态
- 搜索请求和结果数量
- 错误和异常信息

日志会自动推送到 Elasticsearch，可在 Kibana 中查看：

```kibana
class: "com.biliwind.blog.service.elasticsearch.*"
```

### 最佳实践

1. **定期重建索引**: 可以定期重新索引以确保数据一致性
2. **监控索引大小**: 注意索引增长趋势，及时调整 ILM 策略
3. **优化查询**: 使用过滤器减少查询范围
4. **缓存热点**: 对热门搜索词进行缓存
