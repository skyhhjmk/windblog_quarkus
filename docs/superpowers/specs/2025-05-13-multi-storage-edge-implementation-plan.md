# WindBlog 多存储管线与边缘节点 - 实现计划书

> 当前仓库已将早期命名 `storage_provider` / `storage_nodes` 迁移为 `storage_class` / `media.storage_classes`。继续参考本文时，以当前仓库实体和 migration 为准，不要把旧名重新引入新代码。

>
基于设计文档: [2025-05-13-multi-storage-edge-architecture-design.md](./2025-05-13-multi-storage-edge-architecture-design.md)
> 日期: 2025-05-13
> 状态: Step 1.4 ✅, 正在进行步骤1.5
> 执行时需要同步修改状态

---

## 计划总览

| 阶段          | 名称     | 核心交付物                           | 预估复杂度 | 依赖      |
|-------------|--------|---------------------------------|-------|---------|
| **Phase 1** | 核心媒体管线 | 存储抽象层 + WebP转换 + 异步同步 + CDN对接   | ⭐⭐⭐⭐⭐ | 无       |
| **Phase 2** | 边缘节点原型 | Quarkus AOT只读边缘 + gRPC + PG逻辑复制 | ⭐⭐⭐⭐  | Phase 1 |
| **Phase 3** | 多区域多域名 | 区域规则引擎 + 内容过滤 + 多域名路由           | ⭐⭐⭐   | Phase 2 |
| **Phase 4** | 高级特性   | 智能预热/多活/全球负载均衡                  | ⭐⭐    | Phase 3 |

---

## Phase 1: 核心媒体管线改造

### Phase 1 目标验收标准

- [ ] 用户上传图片后，自动生成 WebP + 占位图，同时保留原图
- [ ] 图片同时存储到阿里云 OSS（主）和本地文件系统（备）
- [ ] 非主存储的异步同步通过 RabbitMQ 完成，失败 3 次进死信队列
- [ ] 后台可管理存储节点 CRUD、查看同步状态、手动重触发死信
- [ ] 前台图片访问 URL 自动走 Cloudflare CDN 域名（302 重定向）
- [ ] 视频上传自动提取封面帧
- [ ] 编辑器上传时显示转码进度条
- [ ] `mvn test` 全部通过，`mvn compile` 无错误

---

### Step 1.1: 数据库迁移层

**依赖:** 无
**产出:** 4 个 Liquibase migration SQL 文件

#### 任务 1.1.1: 创建 storage_provider 表

**新建文件:** `src/main/resources/db/migration/058-create-storage-provider.sql`

```sql
-- ============================================
-- 存储提供者配置表
-- ============================================
CREATE TABLE storage_provider
(
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
    display_name    VARCHAR(100) NOT NULL,
    provider_type   VARCHAR(20)  NOT NULL,
    is_enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_primary      BOOLEAN      NOT NULL DEFAULT FALSE,
    role            VARCHAR(20)  NOT NULL DEFAULT 'backup',
    config_json     JSONB        NOT NULL DEFAULT '{}',
    supported_types JSONB        NOT NULL DEFAULT '["*"]',
    cdn_domain      VARCHAR(255)          DEFAULT NULL,
    cdn_enabled     BOOLEAN      NOT NULL DEFAULT FALSE,
    region          VARCHAR(20)  NOT NULL DEFAULT 'global',
    priority        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_storage_provider_enabled ON storage_provider (is_enabled);
CREATE INDEX idx_storage_provider_role ON storage_provider (role);

COMMENT ON TABLE storage_provider IS '存储提供者配置表，支持多种对象存储后端';
COMMENT ON COLUMN storage_provider.name IS '唯一标识符，如 aliyun、local';
COMMENT ON COLUMN storage_provider.provider_type IS '存储类型: oss_aliyun, local_fs';
COMMENT ON COLUMN storage_provider.config_json IS '连接配置JSON，敏感字段可用${env:XXX}引用环境变量';
COMMENT ON COLUMN storage_provider.supported_types IS '支持的MIME类型列表JSON数组';
```

#### 任务 1.1.2: 创建 image_processing_config 表

**新建文件:** `src/main/resources/db/migration/059-create-image-processing-config.sql`

```sql
-- ============================================
-- 图像处理参数配置表
-- ============================================
CREATE TABLE image_processing_config
(
    id           BIGSERIAL PRIMARY KEY,
    config_key   VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT         NOT NULL,
    description  VARCHAR(500)          DEFAULT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO image_processing_config (config_key, config_value, description)
VALUES ('placeholder_max_width', '360', '占位图最大宽度(px)'),
       ('placeholder_quality', '0.6', '占位图JPEG质量(0.0-1.0)'),
       ('placeholder_format', 'jpeg', '占位图输出格式'),
       ('webp_quality', '0.82', 'WebP转换质量(0.0-1.0)'),
       ('webp_method', '4', 'WebP压缩方法(0-6, 0最快6最慢质量最好)'),
       ('cwebp_path', '/usr/bin/cwebp', 'cwebp命令行工具路径'),
       ('ffmpeg_path', '/usr/bin/ffmpeg', 'FFmpeg命令行工具路径');

COMMENT ON TABLE image_processing_config IS '图像/视频处理全局参数配置';
```

#### 任务 1.1.3: media 表新增存储节点字段

**新建文件:** `src/main/resources/db/migration/060-add-media-storage-nodes.sql`

```sql
-- ============================================
-- media 表新增多存储节点追踪字段
-- ============================================
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS storage_nodes JSONB NOT NULL DEFAULT '{}';
ALTER TABLE media
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 1;

COMMENT ON COLUMN media.storage_nodes IS '按节点->变体嵌套的JSONB状态矩阵，记录每个存储节点各变体的同步状态、路径、大小等';
COMMENT ON COLUMN media.version IS '乐观锁版本号，用于并发更新控制';

-- 为已有数据初始化空 storage_nodes（新上传的数据由应用层填充）
UPDATE media
SET storage_nodes = '{}',
    version       = 1
WHERE storage_nodes = '{}'
   OR storage_nodes IS NULL;
```

#### 任务 1.1.4: 新增多区域预留字段

**新建文件:** `src/main/resources/db/migration/061-add-visibility-regions.sql`

```sql
-- ============================================
-- 多区域可见性预留字段 (Phase 3 使用)
-- ============================================
ALTER TABLE media
    ADD COLUMN visibility_regions JSONB;
ALTER TABLE post
    ADD COLUMN visibility_regions JSONB;

COMMENT ON COLUMN media.visibility_regions IS '区域可见性预留字段(JSON数组)，Phase 3 使用';
COMMENT ON COLUMN post.visibility_regions IS '区域可见性预留字段(JSON数组)，Phase 3 使用';
```

#### 任务 1.1.5: 更新 Liquibase master 文件

**修改文件:** `src/main/resources/db/db.changelog-master.xml`

在文件末尾（最后一个 `include` 之后）添加：

```xml

<include file="db/migration/058-create-storage-provider.sql" relativeToChangelogFile="false"/>
<include file="db/migration/059-create-image-processing-config.sql" relativeToChangelogFile="false"/>
<include file="db/migration/060-add-media-storage-nodes.sql" relativeToChangelogFile="false"/>
<include file="db/migration/061-add-visibility-regions.sql" relativeToChangelogFile="false"/>
```

#### 验证方式

```bash
./mvnw compile
# 确认编译无报错即可
```

---

### Step 1.2: 存储抽象层

**依赖:** Step 1.1 ✅
**产出:** 接口、2个实现类、调度服务、实体类、Admin API、单元测试

#### 任务 1.2.1: 定义 StorageProvider 接口

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/StorageProvider.java`

接口方法清单:

- `String getName()` — 返回 provider 名称标识
- `void initialize(StorageProviderConfig config)` — 从配置初始化连接
- `boolean isAvailable()` — 健康检查
- `boolean supportsVariant(String mimeType, String variantType)` — 是否支持某类型+变体组合
- `String upload(InputStream data, String targetPath, String contentType)` — 上传文件，返回存储路径
- `void delete(String storagePath)` — 删除文件
- `boolean exists(String storagePath)` — 检查存在
- `InputStream download(String storagePath)` — 下载文件流
- `String getSignedUrl(String storagePath, Duration expiration)` — 签名URL（私有场景）
- `String getPublicUrl(String storagePath)` — 公开访问URL

**同时新建:**

- `src/main/java/com/biliwind/blog/service/storage/StorageException.java` — 自定义异常
- `src/main/java/com/biliwind/blog/service/storage/StorageProviderConfig.java` — 配置 DTO（从 config_json 反序列化）
- `src/main/java/com/biliwind/blog/service/storage/UploadResult.java` — 上传结果 DTO（path, size, etag）
- `src/main/java/com/biliwind/blog/service/storage/VariantType.java` — 变体枚举（ORIGINAL, WEBP, PLACEHOLDER, COVER, RAW）

#### 任务 1.2.2: 实现 LocalFsStorageProvider

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/LocalFsStorageProvider.java`

实现要点:

- 从 `config_json.rootPath` 获取根目录
- `upload()`: 写入文件到 `{rootPath}/{targetPath}`，创建中间目录
- `download()`: 返回 `Files.newInputStream(path)`
- `delete()`: `Files.deleteIfExists()`
- `exists()`: `Files.exists()`
- `getPublicUrl()`: 返回 `{baseUrl}/{fileName}` 或完整路径
- `supportsVariant()`: 本地文件系统默认支持所有类型
- `isAvailable()`: 检查 rootPath 是否存在且可写

#### 任务 1.2.3: 实现 AliyunOssStorageProvider

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/AliyunOssStorageProvider.java`

实现要点:

- 依赖: `com.aliyun:alibabacloud-oss-v2:0.4.0`（添加到 pom.xml）
- `initialize()`: 解析 config_json → 创建 OSSClient
    - 支持 `${env:XXX}` 环境变量引用格式，解析时替换为真实值
- `upload()`:
    - 小文件（<5MB）: `ossClient.putObject()`
    - 大文件（>=5MB）: 分片上传 `ossClient.uploadPart()`
    - 目标路径格式: `{basePath}{yyyy}/{MM}/{uuid}.{ext}`
- `download()`: `ossClient.getObject()` → InputStream
- `getPublicUrl()`:
    - 如果 `cdn_domain != null && cdn_enabled == true`: 返回 `https://{cdn_domain}/{path}`
    - 否则: 返回 `https://{bucket}.{endpoint.replace("https://","").replace("http://","")}/{path}`
- `supportsVariant()`: 根据 `supported_types` JSON 数组判断 MIME 是否匹配
- `isAvailable()`: 尝试 listObjects 限制 1 条，不抛异常即正常

**pom.xml 修改:**

```xml
<!-- 在 <dependencies> 中添加 -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>alibabacloud-oss-v2</artifactId>
    <version>0.4.0</version>
</dependency>
```

#### 任务 1.2.4: StorageProviderEntity 实体类

**新建文件:** `src/main/java/com/biliwind/blog/model/StorageProvider.java`

映射 `storage_provider` 表，包含所有字段的 JPA 注解。
使用 PanacheEntityBase + 自增 ID。

#### 任务 1.2.5: StorageService 统一调度服务

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/StorageService.java`

核心逻辑:

**初始化 (`@PostConstruct init()`):**

1. 查询 `storage_provider` 表中所有 `is_enabled = true` 的记录，按 `priority ASC` 排序
2. 遍历每条记录:
    - 根据 `provider_type` 创建对应的 Provider 实例
    - 解析 `config_json` 为 `StorageProviderConfig`（处理环境变量替换）
    - 调用 `provider.initialize(config)`
    - 加入 `enabledProviders` 列表
    - 如果 `is_primary == true`，设为 `primaryProvider`
3. 校验: 必须有且仅有 1 个 primary provider，否则抛异常

**uploadToPrimary():**

1. 调用 `primaryProvider.upload(data, targetPath, contentType)`
2. 构造并返回 `UploadResult`
3. 失败时抛出 `StorageException`

**scheduleSync():**

1. 查询所有启用的非 primary provider
2. 对每个 provider × 每个需要同步的 variant:
    - 构造 `StorageSyncMessage`
    - 通过 RabbitMQ `Emitter` 发送到 `storage-sync-tasks` exchange

**executeSync():**

1. 查询 Media 记录，锁定行（SELECT FOR UPDATE）
2. 读取当前 `version` 和 `storage_nodes`
3. CAS 更新目标 variant 状态为 `"syncing"`:
   ```sql
   UPDATE media SET storage_nodes = jsonb_set(...), version = version + 1
   WHERE id = ? AND version = currentVersion
   ```
4. CAS 成功后:
    - 从 primaryProvider 下载对应 variant 文件
    - 上传到目标 provider
    - 成功: 再次原子更新 status = "synced", path, size, version++
    - 失败: 更新 status = "failed"，根据 retryCount 决定重试或死信

**getBestAccessUrl():**

1. 从 `media.storage_nodes` 读取指定 variant 的所有节点状态
2. 按 provider priority 排序
3. 找第一个 `status == "synced"` 的节点
4. 如果该节点 `cdn_enabled == true`: 返回 `https://{cdn_domain}/{path}`
5. 否则返回 provider 的 `getPublicUrl(path)`

**updateVariantStatus():**

- 使用 `jsonb_set` 原子化更新 `storage_nodes` 中指定路径的状态
- 同时递增 `version`
- WHERE 条件包含 `version = expectedVersion` 实现 CAS

#### 任务 1.2.6: Admin 存储 CRUD API

**新建文件:** `src/main/java/com/biliwind/blog/controller/api/admin/AdminStorageController.java`

API 端点清单:

```
GET    /api/admin/storage/providers              -- 列出所有存储节点
POST   /api/admin/storage/providers              -- 新增存储节点
GET    /api/admin/storage/providers/{name}        -- 获取单个节点详情
PUT    /api/admin/storage/providers/{name}        -- 更新节点配置
DELETE /api/admin/storage/providers/{name}        -- 删除节点（禁用而非物理删除）
POST   /api/admin/storage/providers/{name}/test   -- 测试连接
POST   /api/admin/storage/providers/{name}/set-primary -- 设为主存储
GET    /api/admin/storage/sync-status             -- 查看同步状态概览
GET    /api/admin/storage/sync-status/{mediaId}   -- 查看单个媒体的同步细节
```

DTO 类（新建在 `controller/api/admin/dto/storage/` 包下）:

- `StorageProviderCreateRequest.java`
- `StorageProviderUpdateRequest.java`
- `StorageProviderResponse.java`
- `StorageTestResult.java`
- `SyncStatusResponse.java`
- `MediaSyncDetailResponse.java`

#### 任务 1.2.7: 图像处理配置 Admin API

**新建文件:** `src/main/java/com/biliwind/blog/controller/api/admin/AdminImageProcessingController.java`

```
GET    /api/admin/image-processing/config       -- 获取所有配置项
PUT    /api/admin/image-processing/config/{key} -- 更新单个配置项
POST   /api/admin/image-processing/config/test-webp   -- 测试 cwebp 可用性
POST   /api/admin/image-processing/config/test-ffmpeg -- 测试 FFmpeg 可用性
```

**新建实体:** `src/main/java/com/biliwind/blog/model/ImageProcessingConfig.java`

#### 任务 1.2.8: 单元测试

**新建文件:** `src/test/java/com/biliwind/blog/service/storage/`

- `LocalFsStorageProviderTest.java` — 测试本地文件系统的上传/下载/删除/exists
- `AliyunOssStorageProviderMockTest.java` — Mock OSS Client 测试上传下载流程
- `StorageServiceTest.java` — 测试调度逻辑（provider 选择、fallback）
- `VariantTypeTest.java` — 测试变体类型判断

#### 验证方式

```bash
./mvnw test -Dtest=LocalFsStorageProviderTest,StorageServiceTest
./mvnw compile
# 启动后通过 Admin API 或 Swagger UI 测试 CRUD 端点
```

---

### Step 1.3: 图片处理管道

**依赖:** Step 1.1 ✅, Step 1.2 (至少完成 StorageProvider 接口定义和 LocalFS 实现)
**产出:** ImageProcessingService, VideoProcessingService, MediaManagementService 改造

#### 任务 1.3.1: ImageProcessingService — WebP 转换

**新建文件:** `src/main/java/com/biliwind/blog/service/ImageProcessingService.java`

核心方法:

```java
public class ImageProcessingService {

    @Inject
    ConfigResolver config; // 读取 image_processing_config 表的值

    /**
     * 将原始图片转换为 WebP 格式
     * @param originalImagePath 原始图片的绝对路径
     * @param outputWebpPath 输出 WebP 文件的绝对路径
     * @return 转换后的文件路径
     */
    public Path convertToWebp(Path originalImagePath, Path outputWebpPath) throws IOException;

    /**
     * 生成低质量占位图
     * @param originalImagePath 原始图片绝对路径
     * @param outputPlaceholderPath 输出路径
     * @return 生成的占位图路径
     */
    public Path generatePlaceholder(Path originalImagePath, Path outputPlaceholderPath) throws IOException;

    /**
     * 提取图片尺寸信息
     * @param imagePath 图片路径
     * @return int[2] = {width, height}
     */
    public int[] extractDimensions(Path imagePath) throws IOException;
}
```

**convertToWebp() 实现要点:**

1. 从 DB 配置读取 `cwebp_path`, `webp_quality`, `webp_method`
2. 检测 cwebp 工具是否存在且可执行（`Files.isExecutable(Path.of(cwebpPath))`）
3. 构建 Process 命令:
   ```
   {cwebpPath} -q {quality*100} -m {method} -mt {input} -o {output}
   ```
4. 执行进程，等待完成（设置超时 30s）
5. 检查 exit code：
    - 0: 成功，返回输出路径
    - 非 0: 抛出 `ImageProcessingException`，附带 stderr 内容

**generatePlaceholder() 实现要点:**

1. 读取配置: `placeholder_max_width`, `placeholder_quality`, `placeholder_format`
2. 使用 `ImageIO.read()` 读取原图为 `BufferedImage`
3. 计算缩放比例（保持宽高比，宽不超过 maxWidth）
4. 创建目标尺寸 RGB BufferedImage
5. 使用 Graphics2D 高质量渲染绘制
6. 使用 ImageWriter 以 JPEG 格式写入（质量参数来自配置）
7. 返回生成路径

#### 任务 1.3.2: VideoProcessingService — FFmpeg 封面提取

**新建文件:** `src/main/java/com/biliwind/blog/service/VideoProcessingService.java`

核心方法:

```java
public class VideoProcessorService {

    /**
     * 从视频文件提取封面帧
     * @param videoFilePath 视频文件绝对路径
     * @param outputCoverPath 输出封面图片路径
     * @return 封面图片路径
     */
    public Path extractCoverFrame(Path videoFilePath, Path outputCoverPath) throws IOException;
}
```

**实现要点:**

1. 从 DB 配置读取 `ffmpeg_path`
2. 检测 ffmpeg 是否可用
3. 构建 Process 命令:
   ```
   {ffmpegPath} -ss 3 -i {videoPath} -frames:v 1 -q:v 2 {outputPath}
   ```
    - `-ss 3`: 跳过前 3 秒（避免黑屏开头）
    - `-frames:v 1`: 只取 1 帧
    - `-q:v 2`: JPEG 质量 2（VBR 模式下质量较高）
4. 等待执行完成（超时 60s）
5. 验证输出文件存在且大小 > 0
6. 返回封面路径

**容错:** FFmpeg 不可用时抛出明确异常，调用方决定是否跳过 cover 变体。

#### 任务 1.3.3: 重构 MediaManagementService.storeUploadedMedia()

**修改文件:** `src/main/java/com/biliwind/blog/service/MediaManagementService.java`

这是本步骤最核心的改造。需要将现有的 `storeUploadedMedia()` 方法从"直接写本地磁盘"改为"走新的存储管道"：

**新的上传流程:**

```java

@Transactional
public Media storeUploadedMedia(User operator, InputStream source,
                                String fileName, String mimeType,
                                long declaredSize) {
    // === Phase 1: 校验（保留现有逻辑）===
    validateUser(operator);
    String sanitizedFileName = sanitizeFileName(fileName);
    validateExtension(sanitizedFileName);
    UploadRole role = getAndValidateRole(operator);
    String normalizedMime = normalizeMimeType(mimeType);
    validateMimeType(role, normalizedMime);
    long currentUsage = sumUsage(operator.id);

    // === Phase 2: 保存临时文件 ===
    String extension = extractExtension(sanitizedFileName);
    String uuid = UUID.randomUUID().toString();
    String tempOriginalKey = uuid + extension;
    Path tempOriginalPath = uploadRoot.resolve(tempOriginalKey);
    Files.copy(source, tempOriginalPath, StandardCopyOption.REPLACE_EXISTING);
    long size = Files.size(tempOriginalPath);
    validateSizeLimits(role, size, currentUsage);

    // === Phase 3: 变体生成 ===
    Map<VariantType, Path> generatedVariants = new HashMap<>();
    generatedVariants.put(VariantType.ORIGINAL, tempOriginalPath);

    if (normalizedMime.startsWith("image/")) {
        try {
            int[] dimensions = imageProcessingService.extractDimensions(tempOriginalPath);

            // WebP 转换
            Path webpPath = uploadRoot.resolve(uuid + ".webp");
            imageProcessingService.convertToWebp(tempOriginalPath, webpPath);
            generatedVariants.put(VariantType.WEBP, webpPath);

            // 占位图生成
            Path placeholderPath = uploadRoot.resolve(uuid + "_p.jpg");
            imageProcessingService.generatePlaceholder(tempOriginalPath, placeholderPath);
            generatedVariants.put(VariantType.PLACEHOLDER, placeholderPath);

            mediaWidth = dimensions[0];
            mediaHeight = dimensions[1];
        } catch (Exception e) {
            log.warn("图片变体生成失败，仅保留原图: " + e.getMessage());
        }
    }

    if (normalizedMime.startsWith("video/")) {
        try {
            Path coverPath = uploadRoot.resolve(uuid + "_cover.jpg");
            videoProcessorService.extractCoverFrame(tempOriginalPath, coverPath);
            generatedVariants.put(VariantType.COVER, coverPath);
        } catch (Exception e) {
            log.warn("视频封面提取失败: " + e.getMessage());
        }
    }

    // === Phase 4: 写入主存储 ===
    String storageKeyPrefix = generateStorageKeyPrefix(); // e.g., "2025/05/"

    Map<String, Object> storageNodesJson = new LinkedHashMap<>();
    Map<String, Object> primaryNodeJson = new LinkedHashMap<>();

    for (Map.Entry<VariantType, Path> entry : generatedVariants.entrySet()) {
        VariantType variant = entry.getKey();
        Path variantPath = entry.getValue();

        try (InputStream variantStream = Files.newInputStream(variantPath)) {
            String targetPath = storageKeyPrefix + variantPath.getFileName().toString();
            UploadResult result = storageService.uploadToPrimary(
                    null, variant, variantStream, normalizedMime
            );

            Map<String, Object> variantInfo = new LinkedHashMap<>();
            variantInfo.put("path", result.path());
            variantInfo.put("status", "synced");
            variantInfo.put("size", result.size());
            variantInfo.put("etag", result.etag());
            variantInfo.put("synced_at", OffsetDateTime.now().toString());
            primaryNodeJson.put(variant.name().toLowerCase(), variantInfo);
        }
    }

    storageNodesJson.put(storageService.getPrimaryProviderName(), primaryNodeJson);

    // 其他启用节点的初始状态设为 pending
    for (StorageProviderEntity nonPrimary : storageService.getNonPrimaryProviders()) {
        Map<String, Object> pendingNodeJson = new LinkedHashMap<>();
        for (VariantType variant : generatedVariants.keySet()) {
            Map<String, Object> pendingVariant = new LinkedHashMap<>();
            pendingVariant.put("status", "pending");
            pendingVariant.put("path", null);
            pendingNodeJson.put(variant.name().toLowerCase(), pendingVariant);
        }
        storageNodesJson.put(nonPrimary.name, pendingNodeJson);
    }

    // === Phase 5: 创建 Media 记录 ===
    Media media = new Media();
    media.storageKey = uuid + extension;
    media.url = storageService.getBestAccessUrl(null, VariantType.ORIGINAL); // 先用占位
    media.mediaType = parseMediaType(normalizedMime);
    media.mimeType = normalizedMime;
    media.fileName = sanitizedFileName;
    media.size = size;
    media.uploadedBy = operator.id;
    media.width = mediaWidth;
    media.height = mediaHeight;
    media.alt = Collections.emptyMap();
    media.metadata = new HashMap<>();
    media.storageNodes = storageNodesJson;
    media.version = 1;
    media.persist();

    // 更新 url 为正确的访问地址
    media.url = storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
    media.persist();

    // === Phase 6: 发布异步同步消息 ===
    storageService.scheduleSync(media.id, generatedVariants.keySet());

    // 清理临时文件
    cleanupTempFiles(generatedVariants.values());

    return media;
}
```

**需要移除的旧逻辑:**

- 移除 `generateImageVariables()` 方法中的 48px 缩略图和 360px 预览图生成
- 或者保留但标记为 `@Deprecated`，后续确认无引用后删除

**需要修改的 Media 实体:**

**修改文件:** `src/main/java/com/biliwind/blog/model/Media.java`

新增字段:

```java
/** 多存储节点状态矩阵 */
@JdbcTypeCode(SqlTypes.JSON)
@Column(columnDefinition = "jsonb")
public Map<String, Object> storageNodes;

/** 乐观锁版本号 */
@Column
public Integer version;
```

#### 任务 1.3.4: application.properties 新增配置

**修改文件:** `src/main/resources/application.properties`

在末尾追加:

```properties
# ====== 存储服务配置 ======
storage.primary.name=${STORAGE_PRIMARY_NAME:local}

# ====== 外部工具路径 ======
image.processing.cwebp-path=${CWEBP_PATH:/usr/bin/cwebp}
image.processing.ffmpeg-path=${FFMPEG_PATH:/usr/bin/ffmpeg}

# ====== 存储同步消息队列 ======
mp.messaging.outgoing.storage-sync-tasks.connector=smallrye-rabbitmq
mp.messaging.outgoing.storage-sync-tasks.exchange.name=storage-sync
mp.messaging.outgoing.storage-sync-tasks.exchange.type=fanout
mp.messaging.outgoing.storage-sync-tasks.exchange.durable=true

mp.messaging.incoming.storage-sync-in.connector=smallrye-rabbitmq
mp.messaging.incoming.storage-sync-in.queue.name=storage-sync-tasks
mp.messaging.incoming.storage-sync-in.queue.durable=true
mp.messaging.incoming.storage-sync-in.exchange.name=storage-sync
mp.messaging.incoming.storage-sync-in.exchange.type=fanout
mp.messaging.outgoing.storage-sync-in.dead-letter-exchange=storage-sync-dlx
mp.messaging.incoming.storage-sync-in.acknowledgment=manual

mp.messaging.incoming.storage-sync-dlq-in.connector=smallrye-rabbitmq
mp.messaging.incoming.storage-sync-dlq-in.queue.name=storage-sync-dead-letter
mp.messaging.incoming.storage-sync-dlq-in.exchange.name=storage-sync-dlx
mp.messaging.incoming.storage-sync-dlq-in.exchange.type=fanout
mp.messaging.incoming.storage-sync-dlq-in.acknowledgment=auto
```

#### 验证方式

```bash
# 1. 编译通过
./mvnw compile

# 2. 启动应用后通过 Admin API 上传一张测试图片
# 3. 验证:
#    - media 表中有 storage_nodes 字段，primary 节点的 original/webp/placeholder 状态为 synced
#    - 对应存储后端中能找到这些文件
#    - RabbitMQ 中有同步消息被发出并消费
```

---

### Step 1.4: RabbitMQ 异步同步机制

**依赖:** Step 1.2 (StorageService 完成) ✅, Step 1.3 (管道集成完成) ✅
**产出:** Consumer 死信处理、重试机制、Admin 死信管理 API

#### 任务 1.4.1: StorageSyncMessage 定义

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/dto/StorageSyncMessage.java`

```java
public record StorageSyncMessage(
        Long mediaId,
        String providerName,
        String variantType,
        int retryCount
) {
}
```

#### 任务 1.4.2: StorageSyncConsumer

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/StorageSyncConsumer.java`

完整的消费逻辑（参考设计文档第 6.4 节的流程图），关键实现细节:

```java

@ApplicationScoped
public class StorageSyncConsumer {

    @Inject
    StorageService storageService;

    @Inject
    EntityManager entityManager;

    @Inject
    Emitter<StorageSyncMessage> syncEmitter;

    @Incoming("storage-sync-in")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public void consumeSyncTask(Message<StorageSyncMessage> message) {
        StorageSyncMessage msg = message.getPayload();
        log.info("开始处理存储同步: mediaId={}, provider={}, variant={}, retry={}",
                msg.mediaId(), msg.providerName(), msg.variantType(), msg.retryCount());

        try {
            SyncResult result = storageService.executeSync(
                    msg.mediaId(),
                    msg.providerName(),
                    VariantType.valueOf(msg.variantType().toUpperCase()),
                    msg.retryCount()
            );

            if (result.success()) {
                message.ack();
            } else {
                handleFailure(message, msg, result.errorMessage());
            }
        } catch (Exception e) {
            log.error("存储同步异常", e);
            handleFailure(message, msg, e.getMessage());
        }
    }

    private void handleFailure(Message<StorageSyncMessage> message,
                               StorageSyncMessage msg, String errorReason) {
        int nextRetryCount = msg.retryCount() + 1;
        if (nextRetryCount >= 3) {
            log.error("同步达到最大重试次数，发送到死信队列: mediaId={}, provider={}, variant={}",
                    msg.mediaId(), msg.providerName(), msg.variant());
            // 发送到死信 exchange (由 DLQ 配置自动路由)
            message.ack();
        } else {
            log.warn("同步失败，准备第 {} 次重试: mediaId={}, reason={}",
                    nextRetryCount, msg.mediaId(), errorReason);
            // 重新构造消息并重新发送（增加 retryCount）
            StorageSyncMessage retryMsg = new StorageSyncMessage(
                    msg.mediaId(), msg.providerName(), msg.variantType(), nextRetryCount
            );
            syncEmitter.send(retryMsg);
            message.ack();
        }
    }
}
```

#### 任务 1.4.3: StorageDeadLetterConsumer

**新建文件:** `src/main/java/com/biliwind/blog/service/storage/StorageDeadLetterConsumer.java`

- 消费 `storage-sync-dlq-in` 队列
- 将死信消息持久化到 `dead_letter_messages` 表（复用现有表结构或新建专用表）
- 方便 Admin API 查询和管理

#### 任务 1.4.4: Admin 死信队列管理 API

**新建/修改文件:** 在 `AdminStorageController.java` 中添加:

```
GET    /api/admin/storage/dead-letters               -- 列出死信消息（分页）
GET    /api/admin/storage/dead-letters/{id}           -- 单条详情
POST   /api/admin/storage/dead-letters/{id}/retry     -- 手动重触发
POST   /api/admin/storage/dead-letters/batch-retry    -- 批量重触发
DELETE /api/admin/storage/dead-letters/{id}           -- 忽略/删除
```

**重触发实现:**

1. 从死信表中读取原始消息内容
2. 构造新的 `StorageSyncMessage`，`retryCount` 重置为 0
3. 发送到 `storage-sync-tasks` exchange
4. 从死信表中删除该记录

#### 验证方式

```bash
# 1. 仅配置一个 primary (local)，不配置其他 provider
#    → 不应产生任何同步消息

# 2. 配置 primary (local) + 一个非 primary (local-dev)
#    → 上传图片后检查 RabbitMQ 管理界面:
#      - storage-sync-tasks queue 应有消息
#      - 消费后 local-dev 目录应出现复制的文件
#      - media.storage_nodes 中 local-dev 节点状态变为 synced

# 3. 模拟失败: 临时禁用非 primary provider 的目录（chmod 000）
#    → 同步应失败重试 3 次
#    → 第 4 次进入死信队列
#    → 通过 Admin API 查看/重触发
```

---

### Step 1.5: CDN 与访问路由

**依赖:** Step 1.2 (StorageService.getBestAccessUrl 完成) ✅
**产出:** UploadFileController 改造、前台 URL 生成适配

#### 任务 1.5.1: 改造 UploadFileController

**修改文件:** `src/main/java/com/biliwind/blog/controller/UploadFileController.java`

当前行为: 直接从本地磁盘读取文件并返回。
新行为:

1. 先尝试从 `media` 表查找匹配的记录（通过 fileName 匹配 storageKey）
2. 如果找到且 `storage_nodes` 中有 synced 的节点:
    - 返回 **302 重定向** 到 `getBestAccessUrl()` 返回的 CDN URL
3. 如果未找到或没有 synced 节点:
    - 降级为原有逻辑（从本地磁盘读取）

```java

@GET
@Path("/{fileName}")
@Produces(MediaType.WILDCARD)
public Response getFile(@PathParam("fileName") String fileName) {
    // 安全校验（保留现有逻辑）
    if (fileName == null || fileName.isBlank() || ...){
        throw new NotFoundException();
    }

    // 新增: 尝试查找 media 记录并 302 到 CDN
    try {
        Media media = Media.find("storageKey = ?1 AND deletedAt IS NULL", fileName).firstResult();
        if (media != null && media.storageNodes != null) {
            String bestUrl = storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
            if (bestUrl != null && !bestUrl.isBlank()) {
                return Response.status(Response.Status.FOUND)
                        .header("Location", bestUrl)
                        .build();
            }
        }
    } catch (Exception ignored) {
        // 查找失败不影响降级逻辑
    }

    // 降级: 原有的本地磁盘读取逻辑（完全保留）
    java.nio.file.Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
    java.nio.file.Path target = root.resolve(fileName).normalize();
    if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
        throw new NotFoundException();
    }
    String mimeType = probeMimeType(target);
    return Response.ok(target.toFile(), mimeType)
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .build();
}
```

#### 任务 1.5.2: 前台图片 URL 生成适配

**涉及文件:** 所有生成图片 URL 的地方需要改用 `storageService.getBestAccessUrl()`

主要涉及:

- `MediaManagementService.buildPublicUrl()` — 改为调用 `storageService.getBestAccessUrl()`
- `PostController` / `IndexController` 中文章内嵌图片 URL 的处理
- `toDto()` 方法中 URL 的组装

**原则:** 所有面向用户展示的图片 URL 都应经过 `getBestAccessUrl()`，确保优先返回 CDN 地址。

#### 任务 1.5.3: Cloudflare 配置文档

**新建文件:** `docs/cloudflare-setup-guide.md`

内容包括:

1. DNS CNAME 配置步骤（cdn.xxx.com → Cloudflare）
2. Cloudflare 回源配置（Origin Server → 阿里云 OSS 域名）
3. SSL 设置（Full Strict）
4. 缓存规则建议
5. Page Rules 配置示例

#### 验证方式

```bash
# 1. 上传一张图片
# 2. 获取其 URL
# 3. curl -I 该 URL
#    → 应看到 HTTP/1.1 302 Found
#    → Location: https://cdn.xxx.com/2025/05/xxx.webp (或类似)
# 4. 访问 Location 的 URL
#    → 应通过 Cloudflare → 阿里云 OSS 正常返回图片
```

---

### Step 1.6: Admin Flutter 后台 UI

**依赖:** Step 1.2 (Admin API 就绪) ✅, Step 1.4 (死信 API 就绪) ✅
**产出:** 5 个新页面/组件 + 编辑器优化

#### 任务 1.6.1: 存储节点管理页面

**新建文件:** `admin-flutter/lib/pages/storage_providers_page.dart`

功能:

- 存储节点列表（卡片式布局，显示名称、类型、角色、状态、CDN域名）
- 新增/编辑弹窗（表单: name, displayName, providerType下拉, config_json编辑器, supported_types, cdn_domain,
  cdn_enabled开关, region, priority）
- 主存储切换按钮（带确认对话框）
- 测试连接按钮（调用 test API，显示结果 toast）
- 启用/禁用开关

#### 任务 1.6.2: 存储同步监控面板

**新建文件:** `admin-flutter/lib/pages/storage_sync_monitor_page.dart`

功能:

- 数据表格: 行=媒体文件, 列=存储节点×变体类型
- 每个单元格: 彩色状态标签（绿色 synced, 黄色 pending, 红色 failed, 蓝色 syncing, 灰色 skipped）
- 筛选栏: 状态筛选、存储节点筛选、时间范围、MIME 类型
- 统计卡片: 总数、已同步率、失败数
- 点击行展开: 显示详细的 variant 信息（path, size, etag, synced_at）

#### 任务 1.6.3: 死信队列管理页面

**新建文件:** `admin-flutter/lib/pages/storage_dead_letters_page.dart`

功能:

- 死信消息列表（表格: mediaId, provider, variant, retryCount, 错误原因, 时间）
- 单条操作: 重试按钮、忽略按钮
- 批量操作: 全选 → 批量重试按钮
- 确认对话框（重试前提示）

#### 任务 1.6.4: 图片处理配置页面

**新建文件:** `admin-flutter/lib/pages/image_processing_config_page.dart`

功能:

- 配置项列表（键值对形式，可编辑）
- 每项: key（只读）、value（输入框）、description（说明文字）
- 保存按钮（调用 PUT API）
- 测试工具按钮组:
    - "测试 cwebp" → 调用 test-webp API
    - "测试 FFmpeg" → 调用 test-ffmpeg API
    - 结果以 toast 展示

#### 任务 1.6.5: 边缘节点监控页面（基础版）

**新建文件:** `admin-flutter/lib/pages/edge_nodes_page.dart`

功能:

- 已注册边缘节点列表
- 每个节点卡片: ID、区域、状态指示灯（在线/离线）、最后心跳时间、CPU/内存指标条
- 节点详情弹窗: 完整 metrics 表格

#### 任务 1.6.6: 编辑器上传进度条优化

**修改文件:** `admin-flutter/lib/components/media_library_picker.dart` 和相关上传组件

改动点:

1. 上传阶段: 显示线性进度条（determinate）+ "正在上传... {percent}%"
2. 转码阶段: 进度条切换为 indeterminate（CircularProgressIndicator 或自定义动画）+ "正在优化图片..."
3. 成功: 进度条消失，图片插入编辑器
4. 部分成功: 显示警告色 Toast "WebP 转换失败，已保存原图"
5. 失败: 显示错误 Toast + 重试按钮

**注意:** 需要后端 API 配合——上传接口可能需要返回更细粒度的状态信息（如 "uploaded", "processing", "completed", "
partial", "failed"）。这可能需要在 `AdminMediaApiController` 的上传响应中增加 `processing_status` 字段。

#### 验证方式

```bash
cd admin-flutter
flutter analyze
flutter test
# 手动测试每个新页面的功能和交互
```

---

### Step 1.7: 测试与联调

**依赖:** Step 1.1 ~ 1.6 全部完成
**产出:** 测试报告、已知问题清单

#### 任务 1.7.1: 端到端上传测试

测试用例清单:

1. 上传 PNG 图片 → 验证: 原图 + WebP + 占位图均在主存储中，storage_nodes 正确
2. 上传 JPEG 图片 → 同上
3. 上传 GIF 图片 → 验证是否正确处理（GIF 转 WebP 可能需要特殊处理）
4. 上传 MP4 视频 → 验证: 原视频 + 封面帧
5. 上传 PDF 文件 → 验证: 仅 original 变体
6. 上传超限大小的文件 → 验证: 正确拒绝
7. 上传禁止扩展名的文件 → 验证: 正确拒绝
8. 无 cwebp 时上传图片 → 验证: 降级为仅原图，不崩溃
9. 无 FFmpeg 时上传视频 → 验证: 降级为无封面，不崩溃

#### 任务 1.7.2: 多存储同步测试

测试用例清单:

1. 配置 primary=local, backup=local-dev → 上传 → 验证两边都有文件
2. 上传后检查 storage_nodes → primary=synced, backup 初始=pending → 几秒后=synced
3. 禁用 backup 的目标目录 → 上传 → 验证重试 3 次后进入死信
4. 从死信手动重触发 → 恢复目录权限 → 验证同步成功
5. 批量重触发多条死信 → 验证全部处理完毕

#### 任务 1.7.3: CDN 路由测试

测试用例清单:

1. 通过 `/uploads/{key}` 访问已同步的图片 → 302 到 CDN 域名
2. 通过 `/uploads/{key}` 访问未同步的图片 → 降级为本地返回
3. CDN 域名访问 → Cloudflare → OSS → 正常返回图片
4. CDN 域名访问不存在的资源 → 404（Cloudflare 或 OSS 返回）

#### 任务 1.7.4: 并发与边界测试

测试用例清单:

1. 同时上传 10 张图片 → 验证 storage_nodes version 无冲突
2. 同一媒体正在同步时再次触发同步 → 验证幂等丢弃
3. 主存储不可用时上传 → 验证事务回滚，不产生脏数据
4. storage_nodes JSONB 超大场景（模拟 10 个存储节点 × 5 种变体）→ 验证查询性能

#### 任务 1.7.5: 全量回归测试

```bash
./mvnw test
cd admin-flutter && flutter test && flutter analyze
```

确保所有既有测试不受影响。

---

## Phase 2: 边缘节点最小原型

### Phase 2 目标验收标准

- [ ] 边缘节点可作为独立 Quarkus Native 进程运行
- [ ] 边缘节点启动后自动向主节点 gRPC 注册心跳
- [ ] 边缘节点可通过 PG 逻辑复制获取主节点数据
- [ ] 边缘节点能处理文章展示请求（读本地 PG 只读副本）
- [ ] 边缘节点能处理图片访问请求（查 storage_nodes → 302 到 CDN）
- [ ] 边缘节点缓存热点数据到 Redis
- [ ] 主节点 3 次未收到心跳后自动摘除该边缘节点
- [ ] `docker-compose up` 可一键拉起完整集群

---

### Step 2.1: Protobuf + gRPC 基础设施

#### 任务 2.1.1: 定义 Proto 文件

**新建文件:** `edge-node/proto/edge_service.proto`

使用设计文档第 7.3 节的完整 Protobuf 定义。

#### 任务 2.1.2: 主节点 gRPC 服务端

**新建文件:** `src/main/java/com/biliwind/blog/service/edge/EdgeNodeGrpcService.java`

实现 4 个 RPC 方法:

- `Heartbeat()` — 接收心跳，注册/更新节点信息，返回最新配置
- `GetStorageConfig()` — 返回当前所有启用存储节点的配置快照
- `GetMediaMetadata()` — 查询 media 表返回完整元数据
- `DownloadMedia()` — 流式返回文件内容（从主存储或 primary provider 读取）

**辅助组件:**

- `EdgeNodeRegistry.java` — 边缘节点注册表（ConcurrentHashMap + Redis 持久化备份）
- `EdgeNodeInfo.java` — 节点信息数据类（nodeId, region, lastHeartbeat, metrics, status）

**pom.xml 新增依赖:**

```xml

<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-grpc</artifactId>
</dependency>
<dependency>
<groupId>io.quarkus</groupId>
<artifactId>quarkus-grpc-protobuf</artifactId>
</dependency>
```

**application.properties 新增:**

```properties
# gRPC 服务端
quarkus.grpc.server.port=9000
quarkus.grpc.server.enable-reflection=true

# gRPC TLS (生产环境必须开启)
# quarkus.grpc.server.ssl.certificate=/path/to/server.crt
# quarkus.grpc.server.ssl.key=/path/to/server.key
```

#### 任务 2.1.3: TLS 安全配置

最低要求: 单向 TLS（客户端验证服务端证书）
推荐: mTLS（双向证书验证，仅允许持有合法证书的边缘节点连接）

Phase 1 先实现单向 TLS，mTLS 作为后续增强。

---

### Step 2.2: 边缘节点项目

#### 任务 2.2.1: 创建边缘节点 Quarkus 项目

方案选择: 在项目根目录下新建 `edge-node/` 子模块（Maven multi-module 或独立项目）

推荐: **独立 Quarkus 项目**（因为边缘节点的依赖集完全不同——不需要 Admin API、不需要 AI 模块、不需要 Elasticsearch 等）

**项目结构:**

```
edge-node/
├── pom.xml
├── src/
│   └── main/
│       ├── java/com/biliwind/blog/edge/
│       │   ├── EdgeApplication.java
│       │   ├── EdgePostController.java
│       │   ├── EdgeMediaController.java
│       │   ├── EdgeRoutingService.java
│       │   ├── EdgeGrpcClient.java
│       │   ├── EdgeCacheService.java
│       │   └── EdgeHealthCheck.java
│       └── resources/
│           └── application-edge.properties
├── proto/
│   └── edge_service.proto  (复制自主节点)
└── Dockerfile.native
```

#### 任务 2.2.2: EdgeRoutingService

**核心路由逻辑（参考设计文档 7.6 节）:**

1. 接收请求 → 提取媒体 ID 或路径
2. 查 Redis (`media:meta:{id}`)
3. 未命中 → 查本地 PG → 写入 Redis (TTL 1h)
4. 解析 storage_nodes → 按 priority 找 synced 节点
5. cdn_enabled → 302 到 `https://{cdn_domain}/{path}`
6. 无 synced 节点 → gRPC 回源主节点 GetMediaMetadata
7. 更新本地缓存 → 302

#### 任务 2.2.3: EdgeGrpcClient

gRPC 客户端封装:

- 连接管理（自动重连）
- 心跳定时任务（@Scheduled 每 30s）
- 配置缓存（收到 HeartbeatResponse 时更新本地存储配置）
- 降级策略（gRPC 不可用时标记为 OFFLINE 模式）

#### 任务 2.2.4: 只读 API 子集

**EdgePostController.java:**

- `GET /posts` — 文章列表（分页）
- `GET /posts/{slug}` — 文章详情
- `GET /categories` — 分类列表
- `GET /tags` — 标签列表

**EdgeMediaController.java:**

- `GET /media/{id}` — 图片路由（302 到 CDN）
- `GET /uploads/{path}` — 兼容旧路径的路由

所有控制器均为 **只读**，没有任何 POST/PUT/DELETE。

#### 任务 2.2.5: Native Image 构建

**新建文件:** `edge-node/Dockerfile.native`

```dockerfile
FROM quay.io/quarkus/quarkus-maven-image:21-java17 AS build
USER root
WORKDIR /build
COPY pom.xml .
COPY edge-node/pom.xml ./edge-node/
RUN mvns -f edge-node/pom.xml dependency:go-offline -B
COPY src ./src
RUN mvns -f edge-node/pom.xml package -DskipTests -B

FROM registry.access.redhat.com/ubi9/ubi-minimal:9.4
WORKDIR /deploy/
COPY --from=build /build/edge-node/target/*-runner .
EXPOSE 8000
ENTRYPOINT ["./application", "-Dquarkus.http.host=0.0.0.0"]
```

构建命令:

```bash
cd edge-node
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

---

### Step 2.3: 主节点 gRPC 服务完善

#### 任务 2.3.1: 心跳管理

- 接收心跳 → 更新 `EdgeNodeRegistry` 中的最后心跳时间
- 定时任务（@Scheduled 每 60s）: 检查所有节点，超过 90s 未收到心跳的标记为 OFFLINE
- 日志记录节点上下线事件

#### 任务 2.3.2: 配置推送优化

当 `storage_provider` 表发生变化时（CRUD 操作），主动推送给所有在线边缘节点:

- 方案A: 下次心跳响应时附带最新配置（简单，延迟 <= 心跳间隔）
- 方案B: 通过 gRPC Server Streaming 或单独 RPC 推送（实时但复杂）

Phase 1 采用方案A。

---

### Step 2.4: PostgreSQL 逻辑复制

#### 任务 2.4.1: 主节点侧配置

SQL 脚本（DBA 手动执行或初始化脚本）:

```sql
-- 创建复制用户
CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD '{PASSWORD}';

-- 发布需要复制的表
CREATE PUBLICATION edge_replication
    FOR TABLE media, posts, post_revisions, categories, tags,
        post_tags, comments, post_media, storage_class,
        image_processing_config, users, upload_roles;

GRANT USAGE ON SCHEMA public TO replicator;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO replicator;
```

#### 任务 2.4.2: 边缘节点侧配置

```sql
-- 在边缘节点 PG 上执行
CREATE SUBSCRIPTION main_node_subscription
    CONNECTION 'host={MAIN_NODE_HOST} port=5432 dbname=windblog user=replicator password={PASSWORD}'
    PUBLICATION edge_replication
    WITH (
        synchronous_commit = off,
        copy_data = true,
        slot_name = 'edge_{NODE_ID}_subscription'
    );
```

部署前必须确认主库满足逻辑复制前置条件：`wal_level = logical`、`max_replication_slots` 足够容纳所有边缘节点、`max_wal_senders` 足够容纳所有复制连接。云托管 PostgreSQL 开启这些配置通常需要重启实例。

跨地域或公网复制不能按内网延迟估算。复制延迟超过 10 秒时，边缘节点应标记数据不可信，并优先走主节点回源。

#### 任务 2.4.3: 复制健康检查

**新建文件:** `src/main/java/com/biliwind/blog/service/health/ReplicationLagHealthCheck.java`

- 主节点侧: 监控 replication slot 的 WAL 位置
- 边缘节点侧: 查询 `pg_stat_subscription` 检查 lag
- lag 超过 10 秒时标记边缘数据不可信

---

### Step 2.5: Docker Compose 编排

**新建文件:** `docker-compose.edge.yml`

```yaml
version: '3.8'
services:
  main-node:
    build: .
    ports:
      - "8080:8080"
      - "9000:9000"  # gRPC
    environment:
      - QUARKUS_PROFILE=prod
      - ... (所有环境变量)

  edge-node-cn:
    build: ./edge-node
    ports:
      - "8081:8000"
    environment:
      - EDGE_NODE_ID=edge-cn-01
      - EDGE_NODE_REGION=cn
      - MAIN_NODE_GRPC_HOST=main-node
      - MAIN_NODE_GRPC_PORT=9000
      - QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://edge-pg:5432/windblog
      - QUARKUS_REDIS_HOSTS=edge-redis
    depends_on:
      - main-node
      - edge-pg
      - edge-redis

  edge-pg:
    image: postgres:16
    environment:
      POSTGRES_DB: windblog
      POSTGRES_USER: windblog
      POSTGRES_PASSWORD: edge123
    volumes:
      - edge_pg_data:/var/lib/postgresql/data

  edge-redis:
    image: redis:7-alpine

volumes:
  edge_pg_data:
```

---

## Phase 3: 多区域与多域名（数据模型已预留）

> 此阶段在 Phase 1 & 2 稳定运行后启动。以下为高层规划，详细实施计划将在进入本阶段前细化。

### Step 3.1: 区域规则引擎

- `RegionRuleService` — 规则匹配引擎
- 域名 → 区域映射表（数据库配置）
- Accept-Language → 区域备选
- IP GeoIP → 区域兜底（MaxMind GeoIP2 数据库）

### Step 3.2: 内容过滤

- Post/Media 查询注入 `visibility_regions` 过滤条件
- 边缘节点区域感知路由（请求到达时判断区域）
- 同步策略: 按区域选择性同步（中国区域不同步某些文章）

### Step 3.3: 多域名支持

- Quarkus 多虚拟主机配置（基于 Host Header 路由）
- 每域名的独立模板/样式
- SEO hreflang 标签自动生成
- DNS 配置自动化建议

### Step 3.4: Admin 管理

- 区域规则配置 UI
- 域名绑定管理 UI
- 内容区域可见性批量设置

---

## Phase 4: 高级特性（远期规划）

- 存储节点自动扩容
- 智能预热（基于访问模式预测热数据）
- 边缘节点写代理
- 多活主节点（PG 流复制 + 冲突解决）
- 全球负载均衡
- 存储成本优化（冷热数据分层）

---

## 关键里程碑与检查点

| 里程碑                  | 触发条件        | 检查项                                    |
|----------------------|-------------|----------------------------------------|
| **M1: 数据模型就绪**       | Step 1.1 完成 | 4 个 migration 执行成功，新表/字段可用             |
| **M2: 存储抽象层可用**      | Step 1.2 完成 | 可通过 Admin API CRUD 存储节点，LocalFS 上传下载正常 |
| **M3: 图片管道通**        | Step 1.3 完成 | 上传图片自动生成 WebP + 占位图，视频提取封面             |
| **M4: 异步同步跑通**       | Step 1.4 完成 | RabbitMQ 消息正常流转，死信机制正常                 |
| **M5: CDN 分发生效**     | Step 1.5 完成 | 图片访问 302 到 Cloudflare 域名               |
| **M6: Admin UI 完成**  | Step 1.6 完成 | Flutter 后台 5 个新页面可用，编辑器进度条正常           |
| **M7: Phase 1 发布就绪** | Step 1.7 完成 | 全量测试通过，无 P0/P1 bug                     |
| **M8: 边缘节点上线**       | Step 2.5 完成 | docker-compose 一键拉起全集群，边缘节点正常服务        |

---

## 风险缓解检查清单

- [ ] cwebp/FFmpeg 安装检测逻辑已加入启动自检
- [ ] 阿里云 SDK 在 JVM 模式下的兼容性已验证（AOT 问题留到 Phase 2 处理）
- [ ] PG 逻辑复制延迟监控告警已配置
- [ ] RabbitMQ 队列深度监控已配置
- [ ] storage_nodes GIN 索引已创建（针对 JSONB 查询优化）
- [ ] 并发 CAS 更新失败的退避日志已确认
