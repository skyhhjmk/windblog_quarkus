# windblog_quarkus

This project uses Quarkus, the Supersonic Subatomic Java Framework.

If you want to learn more about Quarkus, please visit its website: <https://quarkus.io/>.

## Running the application in dev mode

You can run your application in dev mode that enables live coding using:

```shell script
./mvnw quarkus:dev
```

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

## 核心设计思路 (Core Ideas)

- **模块化构建**：后端采用 Service 模式解耦 AI 与监控逻辑；前端通过多页面分离提升开发效率。
- **性能优先**：通过消息队列处理重型任务，通过 PJAX/缓存技术优化前台渲染。
- **安全性与透明度**：完善的权限校验与监控日志记录，确保系统运行状态可追溯。
- **现代化审美**：后台管理系统遵循 Material 3 设计规范，提供极简、高效的操作体验。