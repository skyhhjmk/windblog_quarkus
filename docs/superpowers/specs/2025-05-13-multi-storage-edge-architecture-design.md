# WindBlog 多存储管线与边缘节点架构设计文档

> 日期: 2025-05-13
> 状态: 待审核
> 范围: Phase 1 - 核心媒体管线改造 + 边缘节点最小原型

---

## 1. 项目背景与目标

### 1.1 现状

WindBlog 当前的媒体管理系统存在以下限制：

- **仅支持本地磁盘存储**，无对象存储抽象层
- **图片处理能力有限**：仅生成 JPEG 缩略图(48px) + 预览图(360px)，无 WebP 转换
- **无多节点分发能力**：所有流量集中在主节点
- **无 CDN 集成**：图片直接由应用服务器提供
- **Media 实体缺少多存储状态追踪**

### 1.2 目标

构建一个完整的**多存储媒体管线系统**，具备以下核心能力：

1. **WebP 图片转换**：上传时同步转换为 WebP 格式，同时保留原图
2. **存储抽象层**：统一的 StorageProvider 接口，支持阿里云 OSS + 本地文件系统
3. **异步同步机制**：主存储同步写入后，通过 RabbitMQ 异步同步到其他存储节点
4. **Cloudflare CDN 对接**：通过 Cloudflare 代理域名直接向客户端提供图片
5. **边缘节点原型**：Quarkus AOT 轻量化只读边缘节点
6. **多区域数据模型预留**：为后续的多区域/多域名功能预留扩展点

### 1.3 技术栈约束

| 组件    | 技术                     | 版本/备注           |
|-------|------------------------|-----------------|
| 后端框架  | Quarkus                | 3.30.8, Java 21 |
| 数据库   | PostgreSQL             | Liquibase 管理迁移  |
| 缓存    | Redis                  | 已集成             |
| 消息队列  | RabbitMQ               | 已集成 (SmallRye)  |
| 图片转换  | cwebp 命令行工具            | 外部依赖，路径可配置      |
| 视频处理  | FFmpeg 命令行工具           | 外部依赖，路径可配置      |
| CDN   | Cloudflare             | 代理自定义域名         |
| 边缘节点  | Quarkus Native (AOT)   | GraalVM 编译      |
| 节点间通讯 | gRPC + Protobuf        | 最小接口集           |
| 数据同步  | PG Logical Replication | 主→边单向复制         |

---

## 2. 整体架构

### 2.1 架构总览

```
                         ┌──────────────────────────────────────────┐
                         │              用户请求                    │
                         └───────────────┬──────────────────────────┘
                                         │
                    ┌────────────────────┼────────────────────────┐
                    ▼                    ▼                        ▼
          ┌─────────────────┐  ┌─────────────────┐    ┌─────────────────┐
          │   xxx.cn (中国)  │  │  xxx.com (国际)  │    │ us.xxx.com (美国)│
          │   边缘节点 A     │  │   边缘节点 B     │    │   边缘节点 C     │
          │ (Quarkus AOT)    │  │ (Quarkus AOT)    │    │ (Quarkus AOT)    │
          │ Redis + PG只读   │  │ Redis + PG只读   │    │ Redis + PG只读   │
          └────────┬────────┘  └────────┬────────┘    └────────┬────────┘
                   │                    │                      │
                   │  302重定向         │  302重定向            │  302重定向
                   │                    │                      │
                   └────────────────────┼──────────────────────┘
                                        ▼
                              ┌─────────────────┐
                              │   Cloudflare     │
                              │   CDN (代理)     │
                              └────────┬────────┘
                                       │ 回源
                                       ▼
                              ┌─────────────────┐
                              │  阿里云 OSS      │
                              │  (Primary 存储)  │
                              └─────────────────┘


  ┌─────────────────────────────────────────────────────────────────┐
  │                     主节点 (Main Node)                          │
  │                                                                  │
  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐   │
  │  │  HTTP Server  │  │   RabbitMQ   │  │   PostgreSQL (主库)   │   │
  │  │  (全功能API)   │  │  (消息队列)  │  │   (读写)              │   │
  │  └──────┬───────┘  └──────┬───────┘  └──────────┬───────────┘   │
  │         │                 │                      │               │
  │         │  上传/管理API    │  存储同步消息         │ 逻辑复制       │
  │         │                 │                      │ (WAL)         │
  │         ▼                 ▼                      ▼               │
  │  ┌────────────────────────────────────────────────────────┐     │
  │  │                  StorageService                        │     │
  │  │  ┌─────────────────┐  ┌──────────────────────────┐     │     │
  │  │  │ AliyunOssProvider│  │ LocalFsStorageProvider   │     │     │
  │  │  │ (Primary)        │  │ (Fallback/Dev)           │     │     │
  │  │  └─────────────────┘  └──────────────────────────┘     │     │
  │  └────────────────────────────────────────────────────────┘     │
  │         │                                                        │
  │         ▼                                                        │
  │  ┌─────────────────┐  ┌──────────────┐  ┌──────────────────┐    │
  │  │ ImagePipeline   │  │ VideoProcessor│  │ gRPC Server      │    │
  │  │ (WebP/占位图)    │  │ (FFmpeg封面)  │  │ (边缘节点通信)    │    │
  │  └─────────────────┘  └──────────────┘  └──────────────────┘    │
  └─────────────────────────────────────────────────────────────────┘
```

### 2.2 核心设计原则

1. **存储对业务透明**：上层服务不关心文件具体存在哪个存储后端
2. **主存储同步、其余异步**：保证上传体验（同步写主存），同时利用异步队列扩展到其他节点
3. **优雅降级**：任何存储节点不可用时自动 fallback 到下一个可用节点
4. **最终一致性**：异步同步容忍短暂的数据不一致（秒级）
5. **乐观锁并发控制**：使用 version 字段防止并发更新冲突
6. **AOT 友好**：边缘节点使用 Quarkus Native 编译，避免反射依赖

---

## 3. 数据模型设计

### 3.1 storage_provider 表（新建）

存储提供者配置表，后台可动态 CRUD。

```sql
CREATE TABLE storage_provider
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,           -- 唯一标识: aliyun, local
    display_name    VARCHAR(100) NOT NULL,                  -- 显示名称
    provider_type   VARCHAR(20)  NOT NULL,                  -- oss_aliyun / local_fs
    is_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,     -- 是否启用
    is_primary      BOOLEAN      NOT NULL DEFAULT FALSE,-- 是否为主存储(全局唯一)
    role            VARCHAR(20)  NOT NULL DEFAULT 'backup', -- primary/backup/archive
    config_json     JSONB        NOT NULL DEFAULT '{}',     -- 连接配置(AK/SK/Bucket等)
    supported_types JSONB        NOT NULL DEFAULT '["*"]',  -- 支持的文件类型
    cdn_domain      VARCHAR(255)          DEFAULT NULL,     -- CDN/访问域名
    cdn_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,    -- 是否启用CDN
    region          VARCHAR(20)  NOT NULL DEFAULT 'global', -- 区域预留
    priority        INT          NOT NULL DEFAULT 0,        -- 同角色优先级
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_storage_provider_enabled ON storage_provider (is_enabled);
CREATE INDEX idx_storage_provider_role ON storage_provider (role);
```

**config_json 结构示例（阿里云 OSS）：**

```json
{
  "endpoint": "oss-cn-hangzhou.aliyuncs.com",
  "bucketName": "windblog-media",
  "accessKeyId": "${env:ALIYUN_ACCESS_KEY_ID}",
  "accessKeySecret": "${env:ALIYUN_ACCESS_KEY_SECRET}",
  "basePath": "media/",
  "useHttps": true
}
```

**config_json 结构示例（本地文件系统）：**

```json
{
  "rootPath": "/data/windblog/uploads",
  "baseUrl": "/uploads",
  "maxSizeBytes": 10737418240
}
```

### 3.2 media 表改造（新增字段）

```sql
ALTER TABLE media
    ADD COLUMN storage_nodes JSONB NOT NULL DEFAULT '{}';
ALTER TABLE media
    ADD COLUMN version INT NOT NULL DEFAULT 1;
```

**storage_providers JSONB 结构（按提供者 → 变体嵌套）：**

```json
{
  "aliyun": {
    "original": {
      "path": "2025/01/abc123.png",
      "status": "synced",
      "size": 2048576,
      "etag": "\"d41d8cd98f00b204e9800998ecf8427e\"",
      "synced_at": "2025-01-15T10:30:00Z"
    },
    "webp": {
      "path": "2025/01/abc123.webp",
      "status": "synced",
      "size": 512000,
      "etag": "\"abc123...\"",
      "synced_at": "2025-01-15T10:30:05Z"
    },
    "placeholder": {
      "path": "2025/01/abc123_p.jpg",
      "status": "synced",
      "size": 25600
    }
  },
  "local": {
    "original": {
      "path": "/uploads/abc123.png",
      "status": "pending",
      "size": null
    },
    "webp": {
      "path": null,
      "status": "pending"
    }
  }
}
```

**变体状态枚举 (variant_status)：**

| 状态        | 含义   | 触发条件               |
|-----------|------|--------------------|
| `pending` | 待同步  | Media 创建时的初始状态     |
| `syncing` | 同步中  | Consumer 开始处理后原子更新 |
| `synced`  | 已同步  | 同步成功完成             |
| `failed`  | 同步失败 | 重试次数 < 3 时可自动重试    |
| `skipped` | 已跳过  | 该存储不支持此变体类型        |

### 3.3 变体 (Variant) 定义

| Variant Key   | 适用 MIME 类型    | 说明          | 生成方式                       |
|---------------|---------------|-------------|----------------------------|
| `original`    | 所有 (`*`)      | 原始文件，不做修改   | 直接保存上传文件                   |
| `webp`        | `image/*`     | WebP 格式转换副本 | cwebp 命令行工具                |
| `placeholder` | `image/*`     | 低质量占位图      | BufferedImage 缩放 + JPEG 编码 |
| `cover`       | `video/*`     | 视频封面帧       | FFmpeg 提取第一有效帧             |
| `raw`         | 非 image/video | 文件/压缩包等     | 直接保存上传文件                   |

### 3.4 图像处理配置表（新建）

```sql
CREATE TABLE image_processing_config
(
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT         NOT NULL,
    description  VARCHAR(500)          DEFAULT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- 初始默认数据
INSERT INTO image_processing_config (config_key, config_value, description)
VALUES ('placeholder_max_width', '360', '占位图最大宽度(px)'),
       ('placeholder_quality', '0.6', '占位图JPEG质量(0.0-1.0)'),
       ('placeholder_format', 'jpeg', '占位图输出格式'),
       ('webp_quality', '0.82', 'WebP转换质量(0.0-1.0)'),
       ('webp_method', '4', 'WebP压缩速度/质量平衡(0-6, 0最快质量最低)'),
       ('cwebp_path', '/usr/bin/cwebp', 'cwebp命令行工具路径'),
       ('ffmpeg_path', '/usr/bin/ffmpeg', 'FFmpeg命令行工具路径');
```

### 3.5 多区域预留字段

```sql
-- media 表新增（Phase 2 使用，Phase 1 仅预留）
ALTER TABLE media
    ADD COLUMN visibility_regions JSONB;

-- post 表新增（Phase 2 使用，Phase 1 仅预留）
ALTER TABLE post
    ADD COLUMN visibility_regions JSONB;
```

**区域常量：**

```java
public final class RegionConstant {
    public static final String GLOBAL = "global";
    public static final String CN = "cn";
    public static final String US = "us";
    public static final String EU = "eu";
}
```

---

## 4. 存储抽象层设计

### 4.1 StorageProvider 接口

```java
public interface StorageProvider {

    String getName();

    void initialize(StorageProviderConfig config);

    boolean isAvailable();

    boolean supportsVariant(String mimeType, String variantType);

    String upload(InputStream data, String targetPath, String contentType)
            throws StorageException;

    void delete(String storagePath) throws StorageException;

    boolean exists(String storagePath);

    InputStream download(String storagePath) throws StorageException;

    String getSignedUrl(String storagePath, Duration expiration);

    String getPublicUrl(String storagePath);
}
```

### 4.2 AliyunOssStorageProvider

基于阿里云 Java SDK 实现，核心要点：

- 使用 `com.aliyun:alibabacloud-oss-v2` 依赖
- 支持分片上传（大文件自动分片）
- 自动生成带日期前缀的目标路径：`{basePath}{yyyy}/{MM}/{uuid}.{ext}`
- `getPublicUrl()` 返回格式：`https://{cdn_domain}/{path}` 或 `https://{bucket}.{endpoint}/{path}`
- 敏感凭证从 `config_json` 读取，支持环境变量引用（如 `${env:XXX}`）

### 4.3 LocalFsStorageProvider

复用现有本地存储逻辑，适配新接口：

- 文件保存到 `config_json.rootPath` 指定目录
- `getPublicUrl()` 返回格式：`{baseUrl}/{fileName}`
- 主要用于开发环境和 fallback 场景

### 4.4 StorageService（统一调度）

```java

@ApplicationScoped
public class StorageService {

    private List<StorageProvider> enabledProviders; // 按优先级排序
    private StorageProvider primaryProvider;

    @PostConstruct
    void init() {
        List<StorageProviderEntity> entities = StorageProviderEntity.list(
                "isEnabled = true ORDER BY priority ASC"
        );
        for (StorageProviderEntity entity : entities) {
            StorageProvider provider = createProvider(entity);
            provider.initialize(parseConfig(entity.configJson));
            enabledProviders.add(provider);
            if (entity.isPrimary) {
                primaryProvider = provider;
            }
        }
    }

    // ====== 写入操作 ======

    public UploadResult uploadToPrimary(Long mediaId, VariantType variant,
                                        InputStream data, String contentType);

    public void scheduleSync(Long mediaId, VariantType variant);

    public SyncResult executeSync(Long mediaId, String providerName,
                                  VariantType variant, int retryCount);

    // ====== 读取操作 ======

    public String getBestAccessUrl(Media media, VariantType variant);

    public InputStream fallbackDownload(Media media, VariantType variant);

    // ====== 状态管理 ======

    @Transactional
    public void updateVariantStatus(Long mediaId, String providerName,
                                    VariantType variant, String status,
                                    String path, Long size, int expectedVersion);

    public MediaSyncStatus getSyncStatus(Long mediaId);
}
```

### 4.5 存储节点选择策略

```
getBestAccessUrl(media, variant):
  1. 从 media.storage_nodes 中读取该 variant 的所有节点状态
  2. 按 provider 优先级排序
  3. 找到第一个 status == "synced" 的节点
  4. 如果该节点 cdn_enabled == true:
       返回 https://{cdn_domain}/{path}
     否则:
       返回 provider.getPublicUrl(path)
  5. 如果没有 synced 节点:
       尝试 fallbackDownload 或返回 gRPC 回源地址
```

---

## 5. 图片处理管道 (Image Pipeline)

### 5.1 上传完整流程

```
用户选择文件
    ↓
[Phase 1: 接收与校验]
    ├─ MIME 类型检测 + 白名单检查（UploadRoleService）
    ├─ 文件大小检查（角色配额）
    ├─ 扩展名安全检查（BANNED_EXTENSIONS）
    └─ 生成 UUID 存储 key
    ↓
[Phase 2: 临时存储]
    └─ 保存原始文件到临时目录 temp/{uuid}_original
    ↓
[Phase 3: 变体生成] ← 前端显示"正在转码..."不定进度条
    │
    ├─ image/* :
    │     ├─ 提取图片尺寸 (BufferedImage.getWidth/getHeight)
    │     ├─ WebP 转换 (调用 cwebp 命令行)
    │     │     参数: -q {quality} -m {method} -o {output} {input}
    │     └─ 占位图生成 (BufferedImage 缩放 + JPEG 编码)
    │           参数: 最大宽度={placeholder_max_width}, 质量={placeholder_quality}
    │
    ├─ video/* :
    │     └─ FFmpeg 提取封面帧
    │           ffmpeg -ss 3 -i {input} -frames:v 1 -q:v 2 {output}
    │           (跳过前3秒避免黑屏, 取信息量最大帧)
    │
    └─ 其他 (*):
          └─ 不生成额外变体, 仅 original
    ↓
[Phase 4: 写入主存储]
    ├─ uploadToPrimary(mediaId, original, originalInputStream)
    ├─ [image] uploadToPrimary(mediaId, webp, webpInputStream)
    ├─ [image] uploadToPrimary(mediaId, placeholder, placeholderInputStream)
    └─ [video] uploadToPrimary(mediaId, cover, coverInputStream)
    ↓
[Phase 5: 创建 Media 记录]
    ├─ 写入 media 表
    │   ├─ storage_nodes: primary 节点的各变体 = "synced"
    │   ├─ storage_nodes: 其他节点的各变体 = "pending"
    │   └─ version = 1
    └─ persist()
    ↓
[Phase 6: 发布异步同步消息]
    └─ for each 非主存储的启用 provider × 每个生成的变体:
          发布 StorageSyncMessage 到 RabbitMQ
```

### 5.2 WebP 转换技术方案

**选择：外部 cwebp 命令行工具**

理由：

- 转换质量最好且稳定
- Google 官方工具，兼容性最强
- 通过 Process 调用，AOT 友好（无需 native 库绑定）
- 路径和参数均可配置

**调用示例：**

```bash
cwebp -q 82 -m 4 -mt input.png -o output.webp
```

参数说明：

- `-q 82`: 质量 82%（对应 0.82）
- `-m 4`: 压缩方法 4（平衡速度和质量，0最快6最慢但质量最好）
- `-mt`: 多线程加速

### 5.3 视频封面提取技术方案

**选择：FFmpeg 命令行工具**

**智能帧提取策略：**

```bash
# 方案1: 跳过开头黑屏段，取第1帧
ffmpeg -ss 3 -i input.mp4 -frames:v 1 -q:v 2 output_cover.jpg

# 方案2: 场景检测，取信息量最大的帧（更优但更慢）
ffmpeg -i input.mp4 -vf "select=gt(scene\,0.3)" -frames:v 1 -q:v 2 output_cover.jpg
```

默认使用方案1（快速），后台可切换到方案2（高质量）。

### 5.4 错误处理策略

| 错误场景            | 处理方式                                             |
|-----------------|--------------------------------------------------|
| cwebp 不存在或执行失败  | 记录错误日志，跳过 WebP 变体，原图正常入库，variant 状态标记为 `skipped` |
| FFmpeg 不存在或执行失败 | 记录错误日志，跳过 cover 变体，视频正常入库                        |
| 主存储上传失败         | 整个上传事务回滚，返回错误给前端                                 |
| 变体生成失败但原图成功     | 原图正常入库，对应 variant 标记为 `failed`，可后续手动重试           |

---

## 6. 异步同步机制 (RabbitMQ)

### 6.1 消息定义

```java
public record StorageSyncMessage(
    Long mediaId,
    String providerName,
    String variantType,
    int retryCount
) {}
```

### 6.2 队列拓扑

```
                    ┌───────────────┐
                    │ storage-sync  │ (Exchange, fanout, durable)
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │storage-sync-   │ (Queue, durable)
                    │tasks           │
                    │                │
                    │ ack=manual     │
                    │ DLX=storage-   │
                    │  sync-dlx      │
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │StorageSync    │ (Consumer)
                    │Consumer       │
                    └───────────────┘

              失败(>=3次) │
                            ▼
                    ┌───────────────┐
                    │storage-sync-  │ (Queue, durable)
                    │dead-letter    │
                    │ ack=auto       │
                    └───────┬───────┘
                            │
                    ┌───────▼───────┐
                    │StorageDeadLetter│ (Consumer)
                    │Consumer        │ (Admin API 手动重触发)
                    └────────────────┘
```

### 6.3 application.properties 配置

```properties
# 存储同步 - 生产者
mp.messaging.outgoing.storage-sync-tasks.connector=smallrye-rabbitmq
mp.messaging.outgoing.storage-sync-tasks.exchange.name=storage-sync
mp.messaging.outgoing.storage-sync-tasks.exchange.type=fanout
mp.messaging.outgoing.storage-sync-tasks.exchange.durable=true

# 存储同步 - 消费者
mp.messaging.incoming.storage-sync-in.connector=smallrye-rabbitmq
mp.messaging.incoming.storage-sync-in.queue.name=storage-sync-tasks
mp.messaging.incoming.storage-sync-in.queue.durable=true
mp.messaging.incoming.storage-sync-in.exchange.name=storage-sync
mp.messaging.incoming.storage-sync-in.exchange.type=fanout
mp.messaging.outgoing.storage-sync-in.dead-letter-exchange=storage-sync-dlx
mp.messaging.incoming.storage-sync-in.acknowledgment=manual

# 死信队列
mp.messaging.incoming.storage-sync-dlq-in.connector=smallrye-rabbitmq
mp.messaging.incoming.storage-sync-dlq-in.queue.name=storage-sync-dead-letter
mp.messaging.incoming.storage-sync-dlq-in.exchange.name=storage-sync-dlx
mp.messaging.incoming.storage-sync-dlq-in.exchange.type=fanout
mp.messaging.incoming.storage-sync-dlq-in.acknowledgment=auto
```

### 6.4 StorageSyncConsumer 核心流程

```
消费 StorageSyncMessage
    ↓
查询 Media 记录（SELECT FOR UPDATE 锁定行）
    ↓
读取当前 version 和 storage_nodes
    ↓
检查目标 provider+variant 的当前状态：
    ├── status 为 "synced" 或 "syncing" → 丢弃消息（ACK），可能重复投递
    ├── status 为 "pending" 或 "failed" → 继续
    └── 该 provider 不存在或已禁用 → 丢弃（ACK）
    ↓
CAS 更新: SET storage_nodes = jsonb_set(..., '{provider,variant,status}', '"syncing"')
         WHERE id = ? AND version = currentVersion
    ↓
    ├── CAS 失败（version 不匹配）→ 丢弃（NACK requeue），其他事务已更新
    └── CAS 成功 → 继续
    ↓
从 Primary Provider 下载对应的变体文件
    ↓
    ├── 下载失败 →
    │   retryCount++
    │   ├── retryCount < 3 → NACK + requeue（延迟重试）
    │   └── retryCount >= 3 → 发送到死信队列 + ACK
    │
    └── 下载成功 →
        上传到目标 Provider
        ↓
        ├── 上传失败 → 同上（重试或死信）
        └── 上传成功 →
            原子化更新:
            UPDATE media
            SET storage_nodes = jsonb_set(
                  jsonb_set(storage_nodes, '{provider,variant,path}', '"new_path"'),
                  '{provider,variant,status}', '"synced"'
                ),
                version = version + 1
            WHERE id = ? AND version = expectedVersion
            ↓
            ACK 消息
```

### 6.5 死信队列管理与手动重触发

**Admin API 端点：**

```
GET  /api/admin/storage/dead-letters          -- 列出所有死信消息
GET  /api/admin/storage/dead-letters/{id}      -- 查看单条详情
POST /api/admin/storage/dead-letters/{id}/retry -- 手动重触发（重新入队）
POST /api/admin/storage/dead-letters/batch-retry -- 批量重触发
DELETE /api/admin/storage/dead-letters/{id}    -- 忽略/删除（确认放弃）
```

重触发时将 `retryCount` 重置为 0，重新发布到 `storage-sync-tasks` exchange。

---

## 7. 边缘节点最小原型

### 7.1 定位与职责

边缘节点是一个**轻量化的只读分发层**，核心职责：

| 职责      | Phase 1 实现程度 | 说明                          |
|---------|--------------|-----------------------------|
| 文章/内容展示 | ✅ 基础实现       | 查询本地 PG 只读副本                |
| 图片访问路由  | ✅ 核心         | 查 storage_nodes → 302 到 CDN |
| 热点数据缓存  | ✅ 基础实现       | Redis 缓存媒体元数据               |
| 心跳注册    | ✅ 最小实现       | gRPC 30s 一次                 |
| 拉取存储配置  | ✅ 最小实现       | gRPC 启动时 + 变更通知             |
| 回源查询    | ✅ 最小实现       | gRPC 查询缺失元数据                |
| 区域路由判断  | 🔸 预留        | 数据模型就绪，过滤逻辑待实现              |
| 写操作处理   | ❌ 不涉及        | 所有写操作走主节点                   |

### 7.2 技术架构

```
┌─────────────────────────────────────────────────┐
│            Edge Node (Quarkus AOT Native)        │
│                                                  │
│  ┌──────────────────────────────────────────┐    │
│  │              HTTP Server (Undertow)       │    │
│  │  ┌─────────────┐ ┌────────────────────┐  │    │
│  │  │ Public API   │ │ Internal API       │  │    │
│  │  │ (文章展示/   │ │ (/health/metrics/  │  │    │
│  │  │  图片路由)   │ │  readiness)        │  │    │
│  │  └─────────────┘ └────────────────────┘  │    │
│  └──────────────────┬───────────────────────┘    │
│                     │                             │
│  ┌──────────────────▼───────────────────────┐    │
│  │            Edge Routing Service           │    │
│  │  ┌──────────┐ ┌──────────┐ ┌───────────┐  │    │
│  │  │ URL Match│ │ Region   │ │ Cache     │  │    │
│  │  │ Router   │ │ Detector │ │ Layer     │  │    │
│  │  └────┬─────┘ └────┬─────┘ └─────┬─────┘  │    │
│  └───────┼────────────┼─────────────┼────────┘    │
│          │            │             │              │
│  ┌───────▼────────────▼─────────────▼────────┐    │
│  │              Data Access Layer            │    │
│  │  ┌──────────┐  ┌──────────┐  ┌─────────┐  │    │
│  │  │PostgreSQL│  │  Redis   │  │ gRPC    │  │    │
│  │  │(只读副本) │  │ (缓存)   │  │ Client  │  │    │
│  │  └──────────┘  └──────────┘  └─────────┘  │    │
│  └───────────────────────────────────────────┘    │
└───────────────────────────────────────────────────┘
```

### 7.3 gRPC 最小接口集

**Protobuf 定义：**

```protobuf
syntax = "proto3";
package windblog.edge;

option java_multiple_files = true;

service EdgeNodeService {

  // 心跳注册 + 状态上报 + 附带最新配置
  rpc Heartbeat(HeartbeatRequest) returns (HeartbeatResponse);

  // 拉取最新存储权重配置表
  rpc GetStorageConfig(ConfigRequest) returns (StorageConfigResponse);

  // 回源查询单个媒体的完整元数据和存储状态
  rpc GetMediaMetadata(MediaQueryRequest) returns (MediaMetadataResponse);

  // 回源流式下载文件（用于边缘节点无缓存时的 fallback）
  rpc DownloadMedia(MediaDownloadRequest) returns (stream DownloadChunk);
}

message HeartbeatRequest {
  string node_id = 1;
  string region = 2;
  int64 timestamp = 3;
  map<string, string> metrics = 4;
}

message HeartbeatResponse {
  bool accepted = 1;
  int32 heartbeat_interval_seconds = 2;
  StorageConfigResponse config = 3;
}

message ConfigRequest {
  string node_id = 1;
}

message StorageConfigResponse {
  repeated StorageNodeConfig nodes = 1;
  int64 config_version = 2;
}

message StorageNodeConfig {
  string name = 1;
  bool is_primary = 2;
  string role = 3;
  int32 priority = 4;
  string cdn_domain = 5;
  bool cdn_enabled = 6;
  repeated string supported_types = 7;
}

message MediaQueryRequest {
  string node_id = 1;
  int64 media_id = 2;
}

message MediaMetadataResponse {
  int64 media_id = 1;
  string storage_key = 2;
  string mime_type = 3;
  map<string, VariantInfo> variants = 4;
  int64 version = 5;
}

message VariantInfo {
  string best_url = 1;
  map<string, NodeVariantStatus> nodes = 2;
}

message NodeVariantStatus {
  string path = 1;
  string status = 2;
  int64 size = 3;
}

message MediaDownloadRequest {
  string node_id = 1;
  int64 media_id = 2;
  string provider_name = 3;
  string variant_type = 4;
}

message DownloadChunk {
  bytes data = 1;
  int64 offset = 2;
  bool is_last = 3;
}
```

### 7.4 PostgreSQL 逻辑复制配置

**主节点侧：**

```sql
-- 创建复制用户（仅需 REPLICATION 权限）
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '{strong_password}';

-- 创建发布（包含边缘节点需要读取的表）
CREATE PUBLICATION edge_replication
FOR TABLE media, posts, post_revisions, categories, tags,
     post_tags, comments, post_media, storage_provider,
     image_processing_config;

-- 授权复制角色读取相关表
GRANT USAGE ON SCHEMA public TO replicator;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO replicator;
```

**边缘节点侧：**

```sql
-- 创建订阅（异步模式，容忍 >200ms 延迟）
CREATE SUBSCRIPTION main_node_subscription
CONNECTION 'host={main_node_ip} port=5432 dbname=windblog user=replicator password={password}'
PUBLICATION edge_replication
WITH (
  synchronous_commit = off,        -- 异步，不等待主节点确认
  copy_data = true                  -- 初始复制已有数据
);
```

**延迟预期分析：**

- WAL 记录传输延迟 ≈ 网络 RTT（200ms+）
- 对于博客场景（低写入频率），WAL 积压量很小
- 边缘节点读取延迟通常在 **200ms ~ 2s** 范围内
- 这是**完全可接受**的最终一致性窗口

### 7.5 边缘节点启动流程

```
Edge Node 启动
    ↓
1. 读取本地配置（节点ID、区域、主节点地址等）
    ↓
2. 连接本地 PostgreSQL（验证逻辑复制状态）
    ↓
3. 连接本地 Redis
    ↓
4. gRPC 连接主节点 → Heartbeat 注册
    │   ├── 成功 → 收到存储配置 + 心跳间隔
    │   └── 失败 → 降级模式（仅使用本地数据，标记为离线）
    ↓
5. 启动 HTTP Server
    ↓
6. 启动心跳定时任务（每 {interval} 秒发送一次）
    ↓
7. 就绪 → 开始接收流量
```

### 7.6 图片访问路由详细流程

```
边缘节点收到 GET /media/{mediaId} 或 GET /uploads/{path}
    ↓
1. 先查 Redis 缓存 key: media:meta:{mediaId}
    ├── 命中 → 解析 storage_nodes
    └── 未命中 → 查本地 PostgreSQL media 表 → 写入 Redis（TTL 1h）
    ↓
2. 解析请求需要的 variant（根据 Accept Header 或查询参数）
    ├── 默认返回 webp（如果浏览器支持）
    └─  可指定 ?variant=original 返回原图
    ↓
3. 在 storage_nodes 中查找最佳访问路径：
    │
    │  for each provider (按 priority 排序):
    │      nodeVariant = storage_nodes[provider][requestedVariant]
    │      if nodeVariant.status == "synced":
    │          if provider.cdn_enabled:
    │              → 302 Redirect to https://{cdn_domain}/{nodeVariant.path}
    │          else:
    │              → 302 Redirect to {provider.getPublicUrl(nodeVariant.path)}
    │          return
    │
    ↓
4. 所有节点均未 synced：
    ├── 如果 original 在 primary 已 synced → 302 到原图 URL
    ├── 否则 → gRPC GetMediaMetadata 回源主节点查询
    │    ├── 主节点有数据 → 更新本地缓存 → 302
    │    └── 主节点也没有 → 返回 404
    ↓
5. （可选）预取优化：
    访问计数器 media:count:{mediaId} in Redis
    当超过阈值（如 1min 50次）→ 触发预取任务
    确保热门资源在所有节点均已同步
```

---

## 8. Cloudflare CDN 集成

### 8.1 DNS 配置

```
cdn.xxx.com → CNAME → windblog-media.pages.cloudflare.com
                           (Cloudflare 代理)
                           → 回源到 阿里云 OSS 绑定域名
```

### 8.2 Cloudflare 配置要点

| 配置项               | 推荐值           | 说明                       |
|-------------------|---------------|--------------------------|
| SSL 模式            | Full (Strict) | Cloudflare ↔ OSS 均 HTTPS |
| 缓存级别              | Standard      | 静态资源长期缓存                 |
| Browser Cache TTL | 1 年           | 图片几乎不变                   |
| Cache Key         | URI + Query   | 简单缓存键                    |
| Compression       | Brotli        | 比 Gzip 压缩率更高             |
| Bot Fight Mode    | 开启            | 防恶意爬虫                    |
| Rate Rules        | 按需配置          | 防 DDoS                   |

### 8.3 后台配置界面

每个 `storage_provider` 记录需要维护：

- `cdn_domain`: 如 `cdn.windblog.com`
- `cdn_enabled`: 是否启用了 Cloudflare（或其他 CDN）代理
- 后续可扩展：优化区域（如亚洲、欧洲、北美分别回源不同 OSS 节点）

---

## 9. Admin 后台管理需求

### 9.1 新增页面/功能

#### 9.1.1 存储节点管理页

**CRUD 操作：**

- 新增/编辑/删除存储提供者
- 设置主存储（全局唯一约束）
- 配置连接信息（AK/SK、Bucket、Endpoint 等）
- 配置支持的文件类型
- 配置 CDN 域名和启用状态
- 启用/禁用开关
- 测试连接按钮

#### 9.1.2 存储同步监控面板

**展示内容：**

- 所有媒体文件的存储节点状态矩阵（表格形式）
    - 行：媒体文件
    - 列：存储节点 × 变体类型
    - 单元格：状态标签（synced/pending/failed/syncing/skipped）
- 筛选：按状态、按存储节点、按时间范围
- 统计：总文件数、各状态数量、同步成功率
- 详情点击：查看某媒体在所有节点的详细状态

#### 9.1.3 死信队列管理

**功能：**

- 列出所有同步失败的死信消息
- 每条消息显示：mediaId、providerName、variantType、retryCount、失败原因、失败时间
- 单条重触发按钮
- 批量重触发（勾选多条 → 一键重试）
- 忽略/删除（确认不再尝试）

#### 9.1.4 图片处理配置

**功能：**

- 查看/编辑 `image_processing_config` 表中的所有配置项
- WebP 质量参数调整
- 占位图尺寸/质量/格式调整
- cwebp/FFmpeg 路径配置
- 保存后即时生效（无需重启）

#### 9.1.5 边缘节点监控（基础版）

**功能：**

- 显示已注册边缘节点列表
- 每个节点：ID、区域、最后心跳时间、在线状态、CPU/内存指标
- 节点详情：查看该节点报告的完整 metrics

### 9.2 Flutter 编辑器优化

**上传体验改进：**

```
阶段1: 用户选择文件
  → 显示确定的上传进度条（0% → 100%）
  → 底部文字: "正在上传..."

阶段2: 上传完成，开始转码
  → 进度条切换为 indeterminate（不确定进度动画）
  → 底部文字: "正在优化图片..." 或 "正在生成预览..."

阶段3a: 转码成功
  → 进度条消失
  → 图片/文件插入编辑器

阶段3b: 转码部分失败（如 WebP 失败但原图成功）
  → 显示警告提示: "WebP 转换失败，已保存原图"
  → 图片仍插入编辑器（使用原图 URL）

阶段3c: 完全失败
  → 显示错误提示 + 重试按钮
```

---

## 10. 分阶段实施路线图

### Phase 1: 核心媒体管线（预计工作量最大）

**目标：** 完成存储抽象层 + WebP 转换 + 异步同步 + CDN 对接

#### Step 1.1: 数据库迁移

- [ ] 创建 `storage_provider` 表
- [ ] 创建 `image_processing_config` 表
- [ ] `media` 表新增 `storage_nodes`(JSONB) 和 `version`(INT) 字段
- [ ] `media` 和 `post` 表新增 `visibility_regions`(JSONB) 预留字段
- [ ] Liquibase changeset 文件

#### Step 1.2: 存储抽象层

- [ ] 定义 `StorageProvider` 接口
- [ ] 实现 `AliyunOssStorageProvider`
- [ ] 实现 `LocalFsStorageProvider`
- [ ] 实现 `StorageService` 统一调度层
- [ ] `StorageProviderEntity` 实体类 + Repository
- [ ] Admin API: 存储节点 CRUD（`AdminStorageController`）
- [ ] 单元测试：各 Provider 的上传/下载/删除

#### Step 1.3: 图片处理管道

- [ ] `ImageProcessingService`：WebP 转换（cwebp 进程调用）
- [ ] `ImageProcessingService`：占位图生成（BufferedImage）
- [ ] `VideoProcessingService`：FFmpeg 封面提取
- [ ] 重构 `MediaManagementService.storeUploadedMedia()` 集成新管道
- [ ] 移除旧的 48px 缩略图生成逻辑
- [ ] Admin API: 图片处理配置 CRUD

#### Step 1.4: RabbitMQ 异步同步

- [ ] `StorageSyncMessage` 定义
- [ ] RabbitMQ 队列/交换机/死信配置（application.properties）
- [ ] `StorageSyncConsumer`：消费 + 同步逻辑
- [ ] `StorageDeadLetterConsumer`：死信队列消费
- [ ] CAS 乐观锁更新 `storage_nodes`
- [ ] 重试机制（最多 3 次）
- [ ] Admin API: 死信队列列表/重触发

#### Step 1.5: CDN 与访问路由

- [ ] `StorageService.getBestAccessUrl()` 实现
- [ ] `UploadFileController` 改造：支持 302 重定向到 CDN
- [ ] Cloudflare 域名解析配置说明文档
- [ ] 前台图片展示 URL 改造（使用新的 getBestAccessUrl）

#### Step 1.6: Admin UI

- [ ] Flutter: 存储节点管理页面
- [ ] Flutter: 存储同步监控面板
- [ ] Flutter: 死信队列管理页面
- [ ] Flutter: 图片处理配置页面
- [ ] Flutter: 编辑器上传进度条优化

#### Step 1.7: 测试与联调

- [ ] 端到端上传测试（原图 + WebP + 占位图）
- [ ] 多存储同步测试（阿里云 + 本地）
- [ ] 同步失败 → 死信 → 重触发 全链路测试
- [ ] CDN 302 重定向测试
- [ ] 并发上传乐观锁测试

---

### Phase 2: 边缘节点最小原型

**目标：** 可运行的 Quarkus AOT 只读边缘节点

#### Step 2.1: Protobuf + gRPC 基础设施

- [ ] 定义 `.proto` 文件（最小接口集）
- [ ] Quarkus gRPC 服务端实现（主节点侧）
- [ ] Quarkus gRPC 客户端实现（边缘节点侧）
- [ ] TLS/mTLS 安全配置（至少 TLS 单向认证）

#### Step 2.2: 边缘节点项目

- [ ] 创建独立 Quarkus 项目（或 Maven module）
- [ ] 只读 API 子集实现（文章展示、图片路由）
- [ ] EdgeRoutingService（URL 匹配 + 区域检测 + 缓存层）
- [ ] Redis 缓存集成
- [ ] PostgreSQL 只读数据源配置
- [ ] Native Image 构建（Dockerfile.native）

#### Step 2.3: 主节点 gRPC 服务

- [ ] `EdgeNodeServiceImpl`：心跳接收 + 配置推送
- [ ] `EdgeNodeServiceImpl`：媒体元数据查询
- [ ] `EdgeNodeServiceImpl`：文件流式下载
- [ ] 边缘节点注册表（内存 + Redis 持久化）

#### Step 2.4: PG 逻辑复制

- [ ] 主节点：创建 PUBLICATION + 复制用户
- [ ] 边缘节点：创建 SUBSCRIPTION
- [ ] 复制健康检查
- [ ] 延迟监控

#### Step 2.5: 边缘节点启动与运维

- [ ] Docker Compose 编排（主节点 + 边缘节点 + Redis + PG）
- [ ] 启动流程自动化
- [ ] 心跳超时检测 + 自动摘除
- [ ] 基础监控指标

---

### Phase 3: 多区域与多域名（数据模型已预留）

**目标：** 区域级内容可见性控制和多域名路由

#### Step 3.1: 区域规则引擎

- [ ] `RegionRuleService`：区域匹配规则定义与评估
- [ ] 域名 → 区域映射配置
- [ ] Accept-Language → 区域备选
- [ ] IP GeoIP → 区域兜底

#### Step 3.2: 内容过滤

- [ ] Post/Media 查询时注入 `visibility_regions` 过滤
- [ ] 边缘节点区域感知路由
- [ ] 同步策略：按区域选择性同步

#### Step 3.3: 多域名支持

- [ ] Quarkus 多虚拟主机配置
- [ ] 每个域名的独立模板/样式
- [ ] SEO 优化（hreflang 标签等）

#### Step 3.4: Admin 管理

- [ ] 区域规则配置界面
- [ ] 域名绑定管理
- [ ] 内容区域可见性批量设置

---

### Phase 4: 高级特性（远期规划）

- [ ] 存储节点自动扩容（发现新 provider 自动加入同步）
- [ ] 智能预热（基于访问模式预测并提前同步热数据）
- [ ] 边缘节点写代理（边缘写 → 异步回主节点）
- [ ] 多活主节点（PG 流复制 + 冲突解决）
- [ ] 全球负载均衡（基于区域和延迟的路由）
- [ ] 存储成本优化（冷数据自动降级到低成本存储）

---

## 11. 关键风险与缓解措施

| 风险                           | 影响              | 缓解措施                                         |
|------------------------------|-----------------|----------------------------------------------|
| cwebp/FFmpeg 未安装或版本不兼容       | 图片/视频处理失败       | 启动时检测工具可用性；降级为跳过该变体                          |
| 阿里云 SDK 在 Quarkus AOT 下兼容性问题 | 边缘节点无法编译 Native | 边缘节点不包含 OSS SDK；仅主节点使用                       |
| PG 逻辑复制在 >200ms 延迟下 lag 过高   | 边缘节点读到过期数据      | 使用异步模式(synchronous_commit=off)；关键数据走 gRPC 回源 |
| RabbitMQ 消息堆积                | 同步严重滞后          | 监控队列深度；告警机制；消费者水平扩展                          |
| storage_nodes JSONB 过大       | 查询性能下降          | 限制单个媒体变体数量；GIN 索引；考虑冷热分离                     |
| 并发更新冲突 (version 竞争)          | CAS 更新失败        | 退避重试；记录冲突日志；必要时悲观锁                           |

---

## 12. 附录

### A. 目录结构（新增/改造文件）

```
src/main/java/com/biliwind/blog/
├── model/
│   └── StorageProvider.java              [新增]
├── service/
│   ├── storage/                           [新增包]
│   │   ├── StorageProvider.java           [新增] 接口
│   │   ├── AliyunOssStorageProvider.java  [新增]
│   │   ├── LocalFsStorageProvider.java    [新增]
│   │   ├── StorageService.java            [新增] 统一调度
│   │   ├── StorageSyncConsumer.java       [新增]
│   │   ├── StorageDeadLetterConsumer.java [新增]
│   │   └── ImageProcessingService.java    [新增]
│   ├── VideoProcessingService.java        [新增]
│   └── edge/                              [新增包]
│       ├── EdgeNodeGrpcService.java       [新增] 主节点gRPC服务
│       └── EdgeRoutingService.java        [新增] 边缘路由
├── controller/api/admin/
│   └── AdminStorageController.java        [新增]
├── dto/
│   └── storage/                           [新增包]
│       ├── StorageSyncMessage.java        [新增]
│       └── ...                            [新增 DTOs]

edge-node/                                  [新增模块/项目]
├── src/main/java/
│   └── com/biliwind/blog/edge/
│       ├── EdgeApplication.java           [新增]
│       ├── EdgePostController.java        [新增]
│       ├── EdgeMediaController.java       [新增]
│       ├── EdgeRoutingService.java        [新增]
│       ├── EdgeGrpcClient.java            [新增]
│       └── EdgeHealthCheck.java           [新增]
├── proto/
│   └── edge_service.proto                 [新增]
└── src/main/resources/
    └── application-edge.properties        [新增]

src/main/resources/db/migration/
├── 058-create-storage-provider.sql        [新增]
├── 059-create-image-processing-config.sql [新增]
├── 060-add-media-storage-nodes.sql        [新增]
└── 061-add-visibility-regions.sql        [新增]
```

### B. 外部依赖（pom.xml 新增）

```xml
<!-- 阿里云 OSS SDK -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>alibabacloud-oss-v2</artifactId>
    <version>0.4.0</version>
</dependency>

        <!-- gRPC (主节点服务端 + 边缘节点客户端) -->
<dependency>
<groupId>io.quarkus</groupId>
<artifactId>quarkus-grpc</artifactId>
</dependency>

        <!-- Protobuf -->
<dependency>
<groupId>io.quarkus</groupId>
<artifactId>quarkus-grpc-protobuf</artifactId>
</dependency>
```

### C. 环境变量清单（新增）

| 变量名                        | 用途          | 示例                     |
|----------------------------|-------------|------------------------|
| `ALIYUN_ACCESS_KEY_ID`     | 阿里云 AK      | LTAI...                |
| `ALIYUN_ACCESS_KEY_SECRET` | 阿里云 SK      | xxx...                 |
| `CWEBP_PATH`               | cwebp 工具路径  | /usr/bin/cwebp         |
| `FFMPEG_PATH`              | FFmpeg 工具路径 | /usr/bin/ffmpeg        |
| `EDGE_NODE_ID`             | 边缘节点唯一 ID   | edge-cn-01             |
| `EDGE_NODE_REGION`         | 边缘节点所属区域    | cn                     |
| `MAIN_NODE_GRPC_HOST`      | 主节点 gRPC 地址 | main.windblog.internal |
| `MAIN_NODE_GRPC_PORT`      | 主节点 gRPC 端口 | 9000                   |
| `PG_REPLICATION_PASSWORD`  | PG 复制用户密码   | xxx...                 |
