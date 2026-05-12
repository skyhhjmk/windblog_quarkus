package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.controller.api.admin.dto.AdminMediaDtos;
import com.biliwind.blog.model.*;
import io.quarkus.panache.common.Page;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 媒体文件管理服务
 * 负责媒体文件的上传、存储、引用管理和图像预览生成
 */
@ApplicationScoped
public class MediaManagementService {

    // 禁止上传的文件扩展名列表，防止上传可执行文件
    private static final Set<String> BANNED_EXTENSIONS = Set.of(
            ".exe", ".dll", ".cmd", ".bat", ".ps1", ".com", ".class", ".sh", ".jar", ".py"
    );
    // 需要手动加载原图的大小阈值（5MB）
    private static final long MANUAL_LOAD_THRESHOLD_BYTES = 5L * 1024L * 1024L;

    // 识别 Markdown 和 HTML 中 URL 的正则表达式
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:src|href|url|data-src)=['\"]([^'\"\\s>]+)['\"]|!\\[.*?\\]\\(([^)\\s]+)\\)|\\[.*?\\]\\(([^)\\s]+)\\)"
    );

    @ConfigProperty(name = "media.upload.dir")
    String mediaUploadDir;

    @ConfigProperty(name = "media.upload.path")
    String mediaUploadPath;

    @Inject
    UploadRoleService uploadRoleService;

    private Path uploadRoot;
    private String normalizedPublicPath;

    /**
     * 批量重试导入失败的媒体
     * 查询所有 metadata 中 importStatus="failed" 的媒体并逐个重试
     * @return 批量重试结果
     */
    @Transactional
    public AdminMediaDtos.BatchRetryResult batchRetryFailedImports() {
        String nativeQuery = "select * from media where deleted_at is null and metadata->>'importStatus' = 'failed' order by created_at desc";
        List<Media> failedMedias = Media.getEntityManager()
                .createNativeQuery(nativeQuery, Media.class)
                .getResultList();
        int totalCount = failedMedias.size();
        int successCount = 0;
        int failedCount = 0;
        List<AdminMediaDtos.BatchRetryItemResult> results = new ArrayList<>();
        for (Media media : failedMedias) {
            Long mediaId = media.id;
            String fileName = media.fileName;
            boolean success = false;
            String errorMessage = null;
            try {
                retryImport(mediaId);
                success = true;
                successCount++;
            } catch (IOException e) {
                failedCount++;
                errorMessage = e.getMessage();
            } catch (Exception e) {
                failedCount++;
                errorMessage = e.getMessage();
            }
            AdminMediaDtos.BatchRetryItemResult itemResult = new AdminMediaDtos.BatchRetryItemResult(
                    mediaId,
                    fileName,
                    success,
                    errorMessage);
            results.add(itemResult);
        }
        return new AdminMediaDtos.BatchRetryResult(totalCount, successCount, failedCount, results);
    }

    /**
     * 初始化媒体存储目录和公共访问路径
     */
    @PostConstruct
    void init() {
        // 使用绝对路径并规范化，防止路径遍历攻击
        uploadRoot = Paths.get(mediaUploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建媒体存储目录：" + uploadRoot, e);
        }
        // 规范化公共路径，移除末尾斜杠
        normalizedPublicPath = normalizePublicPath(mediaUploadPath);
    }

    /**
     * 获取媒体文件列表
     * @param page 页码（从 1 开始）
     * @param pageSize 每页数量
     * @param unreferencedOnly 是否仅返回未引用的媒体
     * @return 媒体列表结果
     */
    @Transactional
    public AdminMediaDtos.MediaListResult listMedia(int page, int pageSize, boolean unreferencedOnly, boolean failedOnly) {
        // 确保页码和每页数量在有效范围内
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String where = "deletedAt is null";
        // 构建查询条件，筛选未引用的媒体
        if (unreferencedOnly) {
            where += " and id not in (select pm.media.id from PostMedia pm)";
        }
        List<Media> medias;
        long total;
        if (failedOnly) {
            // 使用原生 SQL 处理 JSONB 查询，因为 HQL 对 JSONB 支持有限
            String nativeWhere = "deleted_at is null and metadata->>'importStatus' = 'failed'";
            if (unreferencedOnly) {
                nativeWhere += " and id not in (select media_id from post_media)";
            }

            // 执行计数查询
            total = ((Number) Media.getEntityManager()
                    .createNativeQuery("select count(*) from media where " + nativeWhere)
                    .getSingleResult()).longValue();

            // 执行分页查询
            medias = Media.getEntityManager()
                    .createNativeQuery("select * from media where " + nativeWhere + " order by created_at desc", Media.class)
                    .setFirstResult((safePage - 1) * safeSize)
                    .setMaxResults(safeSize)
                    .getResultList();
        } else {
            var query = Media.find(where + " order by createdAt desc");
            total = query.count();
            medias = query.page(Page.of(safePage - 1, safeSize)).list();
        }

        List<Long> ids = medias.stream().map(m -> m.id).collect(Collectors.toList());
        Map<Long, List<PostMedia>> referencesByMedia;
        // 批量查询引用关系，避免 N+1 查询
        if (!ids.isEmpty()) {
            List<PostMedia> references = PostMedia.list("media.id in ?1", ids);
            referencesByMedia = references.stream()
                    .collect(Collectors.groupingBy(pm -> pm.media.id));
        } else {
            referencesByMedia = new HashMap<>();
        }
        // 转换为 DTO 对象
        List<AdminMediaDtos.MediaItem> items = medias.stream()
                .map(media -> toDto(media, referencesByMedia.getOrDefault(media.id, Collections.emptyList())))
                .collect(Collectors.toList());
        return new AdminMediaDtos.MediaListResult(items, total, safePage, safeSize);
    }

    /**
     * 重建媒体引用关系
     * 扫描所有文章，重新建立媒体与文章的引用关系
     * @return 扫描结果统计信息
     */
    @Transactional
    public AdminMediaDtos.MediaScanResult rebuildReferences() {
        // 删除所有现有引用关系
        PostMedia.deleteAll();

        List<Post> posts = Post.list("deletedAt is null");
        List<Media> medias = Media.list("deletedAt is null");

        // 构建媒体索引，提高匹配效率
        Map<String, List<Media>> mediaByPathKey = new HashMap<>();
        for (Media m : medias) {
            Set<String> keys = getMediaIdentityKeys(m);
            for (String key : keys) {
                mediaByPathKey.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
        }

        long postsScanned = 0;
        long referencesCreated = 0;
        Set<Long> referencedMediaIds = new HashSet<>();

        // 遍历所有文章
        for (Post post : posts) {
            PostRevision revision = post.currentRevision;
            if (revision == null || revision.contentMarkdown == null || revision.contentMarkdown.isEmpty()) {
                continue;
            }
            postsScanned++;

            String content = normalizeContent(revision.contentMarkdown);
            Set<String> extractedKeys = extractReferenceKeys(content);

            Set<Long> matchedInPost = new HashSet<>();
            for (String key : extractedKeys) {
                List<Media> matches = mediaByPathKey.get(key);
                if (matches != null) {
                    for (Media m : matches) {
                        if (matchedInPost.add(m.id)) {
                            persistReference(post, m);
                            referencesCreated++;
                            referencedMediaIds.add(m.id);
                        }
                    }
                }
            }
        }

        long unreferenced = medias.size() - referencedMediaIds.size();
        return new AdminMediaDtos.MediaScanResult(postsScanned, referencesCreated, Math.max(0, unreferenced));
    }

    /**
     * 同步文章的媒体引用关系
     * 当文章内容更新时，重新建立媒体与文章的引用关系
     * @param post 文章对象
     * @param contentMap 文章内容映射
     */
    @Transactional
    public void syncPostReferences(Post post, Map<String, String> contentMap) {
        if (post == null || post.id == null) {
            return;
        }
        // 删除旧引用
        PostMedia.delete("post.id = ?1", post.id);

        if (contentMap == null || contentMap.isEmpty()) {
            return;
        }

        List<Media> medias = Media.list("deletedAt is null");
        Map<String, List<Media>> mediaByPathKey = new HashMap<>();
        for (Media m : medias) {
            Set<String> keys = getMediaIdentityKeys(m);
            for (String key : keys) {
                mediaByPathKey.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
            }
        }

        String content = normalizeContent(contentMap);
        Set<String> extractedKeys = extractReferenceKeys(content);

        Set<Long> matchedInPost = new HashSet<>();
        for (String key : extractedKeys) {
            List<Media> matches = mediaByPathKey.get(key);
            if (matches != null) {
                for (Media m : matches) {
                    if (matchedInPost.add(m.id)) {
                        persistReference(post, m);
                    }
                }
            }
        }
    }

    /**
     * 存储上传的媒体文件
     * @param operator 操作用户
     * @param source 文件输入流
     * @param fileName 文件名
     * @param mimeType MIME 类型
     * @param declaredSize 声明的文件大小
     * @return 媒体对象
     */
    @Transactional
    public Media storeUploadedMedia(User operator, InputStream source, String fileName, String mimeType, long declaredSize) {
        // 验证用户身份
        if (operator == null || operator.id == null) {
            throw new ForbiddenException("上传操作需要有效的用户身份");
        }
        // 清理文件名，防止路径遍历攻击
        String sanitizedFileName = sanitizeFileName(fileName);
        // 检查文件扩展名是否被禁止
        validateExtension(sanitizedFileName);
        String roleName = operator.roleName;
        if (roleName == null || roleName.isBlank()) {
            roleName = RoleConstant.USER;
        }
        // 获取用户的上传角色权限
        UploadRole role = uploadRoleService.findByName(roleName);
        if (role == null) {
            role = uploadRoleService.findByName(RoleConstant.USER);
        }
        if (role == null || !role.canUpload) {
            throw new ForbiddenException("当前角色不允许上传媒体");
        }
        // 规范化 MIME 类型
        String normalizedMime = (mimeType == null || mimeType.isBlank()) ? "" : mimeType.trim().toLowerCase();
        // 检查 MIME 类型是否在允许列表中
        if (!role.allowedMimeTypes.isEmpty() && !role.allowedMimeTypes.contains(normalizedMime)) {
            throw new ForbiddenException("当前角色不允许上传该类型文件");
        }
        // 计算当前用户已使用的存储空间
        long currentUsage = sumUsage(operator.id);

        String extension = extractExtension(sanitizedFileName);
        // 使用 UUID 生成唯一的存储键，防止文件名冲突
        String storageKey = UUID.randomUUID().toString() + extension;
        Path target = uploadRoot.resolve(storageKey);
        // 写入文件到磁盘
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            source.transferTo(output);
        } catch (IOException e) {
            deleteTarget(target);
            throw new IllegalStateException("写入媒体文件失败", e);
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            deleteTarget(target);
            throw new IllegalStateException("无法获取文件大小", e);
        }

        // 验证单文件大小限制
        if (role.maxSingleUploadBytes != null && role.maxSingleUploadBytes > 0 && size > role.maxSingleUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("单文件大小超出限制");
        }
        // 验证总上传大小限制
        if (role.maxTotalUploadBytes != null && role.maxTotalUploadBytes > 0
                && currentUsage + size > role.maxTotalUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("总上传大小超出限制");
        }

        // 创建媒体对象并保存到数据库
        Media media = new Media();
        media.storageKey = storageKey;
        media.url = buildPublicUrl(storageKey);
        media.mediaType = parseMediaType(normalizedMime);
        media.mimeType = normalizedMime;
        media.fileName = sanitizedFileName;
        media.size = size;
        media.uploadedBy = operator.id;
        media.width = null;
        media.height = null;
        media.alt = Collections.emptyMap();
        Map<String, Object> metadata = new HashMap<>();
        media.metadata = metadata;
        media.createdAt = OffsetDateTime.now();
        media.deletedAt = null;
        // 如果是图片，提取尺寸信息并生成预览图
        if (normalizedMime.startsWith("image/")) {
            try {
                BufferedImage image = ImageIO.read(target.toFile());
                if (image != null) {
                    media.width = image.getWidth();
                    media.height = image.getHeight();
                    generateImageVariants(image, storageKey, metadata);
                }
            } catch (IOException ignored) {
            }
        }
        media.persist();
        return media;
    }

    /**
     * 从 URL 导入外部媒体文件
     *
     * @param operator 操作用户
     * @param fileUrl  外部文件 URL
     * @return 导入成功的媒体对象
     * @throws IOException 下载或存储失败时抛出
     */
    @Transactional
    public Media importFromUrl(User operator, String fileUrl) throws IOException {
        String currentUrl = fileUrl;
        HttpURLConnection conn = null;
        int redirectCount = 0;

        // 手动处理重定向，主要为了支持跨协议（HTTP -> HTTPS）
        while (redirectCount < 5) {
            URL url = URI.create(currentUrl).toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            // 允许自动重定向（同协议下）
            conn.setInstanceFollowRedirects(true);
            // 增加 User-Agent 伪装，防止被部分服务器拦截
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            conn.connect();
            int code = conn.getResponseCode();

            // 检查重定向
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                if (location != null) {
                    if (!location.startsWith("http")) {
                        // 处理相对路径重定向
                        location = URI.create(currentUrl).resolve(location).toString();
                    }
                    currentUrl = location;
                    redirectCount++;
                    conn.disconnect();
                    continue;
                }
            }

            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("下载文件失败，HTTP 状态码: " + code + " URL: " + currentUrl);
            }
            break;
        }

        String contentType = conn.getContentType();
        // 校验 Content-Type，如果下载到的是 HTML，通常是防盗链拦截、授权页或自定义 404
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            throw new IOException("下载内容疑似为 HTML 页面而非媒体文件，已拦截。URL: " + currentUrl);
        }

        String fileName = extractFileNameFromUrl(currentUrl);
        long contentLength = conn.getContentLengthLong();

        try (InputStream is = conn.getInputStream()) {
            return storeUploadedMedia(operator, is, fileName, contentType, contentLength);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 创建一个标记为“导入失败”的占位媒体记录
     *
     * @param operator  操作用户
     * @param sourceUrl 原始来源 URL
     * @param error     错误描述
     * @return 媒体对象
     */
    @Transactional
    public Media markAsImportFailed(User operator, String sourceUrl, String error) {
        Media media = new Media();
        media.storageKey = "FAILED_" + UUID.randomUUID().toString();
        media.url = sourceUrl; // 保留原始 URL 以便后续显示或重试
        media.mediaType = 2; // 其他
        media.fileName = extractFileNameFromUrl(sourceUrl);
        media.size = 0L;
        media.uploadedBy = operator.id;
        media.createdAt = OffsetDateTime.now();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("importStatus", "failed");
        metadata.put("sourceUrl", sourceUrl);
        metadata.put("importError", error);
        media.metadata = metadata;

        media.persist();
        return media;
    }

    /**
     * 重试导入失败的媒体
     *
     * @param mediaId 媒体 ID
     * @return 导入成功的媒体对象
     * @throws IOException 导入失败时抛出
     */
    @Transactional
    public Media retryImport(Long mediaId) throws IOException {
        Media media = Media.findById(mediaId);
        if (media == null) {
            throw new BadRequestException("媒体不存在");
        }

        Map<String, Object> metadata = media.metadata != null ? new HashMap<>(media.metadata) : new HashMap<>();
        String status = String.valueOf(metadata.get("importStatus"));
        if (!"failed".equals(status)) {
            throw new BadRequestException("该媒体并未处于导入失败状态");
        }

        String sourceUrl = String.valueOf(metadata.get("sourceUrl"));
        if (sourceUrl == null || sourceUrl.isBlank() || sourceUrl.equals("null")) {
            // 如果 metadata 中没有 sourceUrl，尝试使用 media.url
            sourceUrl = media.url;
        }

        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new BadRequestException("找不到原始来源 URL");
        }

        User operator = User.findById(media.uploadedBy);
        if (operator == null) {
            operator = User.find("roleName = ?1", RoleConstant.SUPER_ADMIN).firstResult();
        }

        try {
            Media newMedia = importFromUrl(operator, sourceUrl);
            // 导入成功，更新原记录
            String oldUrl = media.url;
            String newUrl = newMedia.url;

            media.storageKey = newMedia.storageKey;
            media.url = newUrl;
            media.mediaType = newMedia.mediaType;
            media.mimeType = newMedia.mimeType;
            media.fileName = newMedia.fileName;
            media.size = newMedia.size;
            media.width = newMedia.width;
            media.height = newMedia.height;
            media.metadata = newMedia.metadata; // 包含预览图等
            media.persist();

            // 全局替换文章中的引用地址
            if (oldUrl != null && !oldUrl.equals(newUrl)) {
                updatePostReferences(oldUrl, newUrl);
            }

            return media;
        } catch (Exception e) {
            metadata.put("importError", e.getMessage());
            metadata.put("lastRetryAt", OffsetDateTime.now().toString());
            media.metadata = metadata;
            media.persist();
            throw new IOException("重试导入仍然失败: " + e.getMessage(), e);
        }
    }

    /**
     * 全局替换文章内容中的媒体引用地址
     */
    @Transactional
    protected void updatePostReferences(String oldUrl, String newUrl) {
        if (oldUrl == null || newUrl == null || oldUrl.equals(newUrl)) {
            return;
        }

        // 查找所有包含 oldUrl 的版本
        // 使用原生 SQL 查找 ID 提高效率（PostgreSQL 语法）
        List<Object> revisionIds = Media.getEntityManager()
                .createNativeQuery("SELECT id FROM post_revisions WHERE content_markdown::text LIKE :url")
                .setParameter("url", "%" + oldUrl + "%")
                .getResultList();

        for (Object rawId : revisionIds) {
            Long id = ((Number) rawId).longValue();
            PostRevision revision = PostRevision.findById(id);
            if (revision != null && revision.contentMarkdown != null) {
                boolean changed = false;
                Map<String, String> newContent = new HashMap<>(revision.contentMarkdown);
                for (Map.Entry<String, String> entry : newContent.entrySet()) {
                    String val = entry.getValue();
                    if (val != null && val.contains(oldUrl)) {
                        newContent.put(entry.getKey(), val.replace(oldUrl, newUrl));
                        changed = true;
                    }
                }
                if (changed) {
                    revision.contentMarkdown = newContent;
                    revision.persist();
                }
            }
        }
    }

    /**
     * 从 URL 中提取文件名
     */
    private String extractFileNameFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "unknown";
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < url.length() - 1) {
            String name = url.substring(lastSlash + 1);
            int queryParam = name.indexOf('?');
            if (queryParam > 0) {
                return name.substring(0, queryParam);
            }
            return name;
        }
        return "file";
    }

    /**
     * 构建公共访问 URL
     * @param storageKey 存储键
     * @return 公共访问 URL
     */
    private String buildPublicUrl(String storageKey) {
        if (normalizedPublicPath.endsWith("/")) {
            return normalizedPublicPath + storageKey;
        }
        return normalizedPublicPath + "/" + storageKey;
    }

    /**
     * 计算用户已使用的存储空间
     * @param userId 用户 ID
     * @return 已使用的字节数
     */
    private long sumUsage(Long userId) {
        if (userId == null) {
            return 0;
        }
        Long result = Media.find(
                "select coalesce(sum(size), 0) from Media where uploadedBy = ?1 and deletedAt is null",
                userId
        ).project(Long.class).firstResult();

        return result == null ? 0L : result;
    }

    /**
     * 标准化内容以便引用检查
     * @param content 内容映射
     * @return 标准化后的内容字符串
     */
    private String normalizeContent(Map<String, String> content) {
        return content.values().stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 检查内容中是否包含对指定媒体的引用
     * @param normalizedContent 标准化后的内容
     * @param media 媒体对象
     * @return 是否包含引用
     */
    private boolean containsReference(String normalizedContent, Media media) {
        if (media == null || normalizedContent.isBlank()) {
            return false;
        }
        // 检查 URL、存储键和文件名是否出现在内容中
        if (media.url != null && !media.url.isBlank() && normalizedContent.contains(media.url.toLowerCase())) {
            return true;
        }
        if (media.storageKey != null && normalizedContent.contains(media.storageKey.toLowerCase())) {
            return true;
        }
        if (media.fileName != null && !media.fileName.isBlank()
                && normalizedContent.contains(media.fileName.toLowerCase())) {
            return true;
        }
        return false;
    }

    /**
     * 持久化媒体引用关系
     * @param post 文章对象
     * @param media 媒体对象
     */
    private void persistReference(Post post, Media media) {
        PostMedia entry = new PostMedia();
        entry.id = new PostMediaId(post.id, media.id);
        entry.post = post;
        entry.media = media;
        entry.usageType = 0;
        entry.position = null;
        entry.createdAt = OffsetDateTime.now();
        entry.persist();
    }

    /**
     * 将媒体对象转换为 DTO
     * @param media 媒体对象
     * @param references 引用列表
     * @return 媒体 DTO 对象
     */
    public AdminMediaDtos.MediaItem toDto(Media media, List<PostMedia> references) {
        long referencedBy = references == null ? 0 : references.size();
        return new AdminMediaDtos.MediaItem(
                media.id,
                media.storageKey,
                media.url,
                metadataString(media, "thumbnailUrl"),
                metadataString(media, "previewUrl"),
                requiresManualOriginal(media),
                media.fileName,
                media.mimeType,
                media.size,
                media.mediaType,
                media.width,
                media.height,
                media.uploadedBy,
                findUploaderName(media.uploadedBy),
                media.createdAt,
                referencedBy > 0,
                references == null ? Collections.emptyList()
                        : references.stream()
                        .map(this::toReferenceDto)
                        .collect(Collectors.toList()),
                media.metadata
        );
    }

    /**
     * 将引用对象转换为 DTO
     * @param pm 引用对象
     * @return 引用 DTO 对象
     */
    private AdminMediaDtos.MediaReferenceItem toReferenceDto(PostMedia pm) {
        String title = pickTitle(pm.post.title);
        return new AdminMediaDtos.MediaReferenceItem(
                pm.post.id,
                pm.post.slug,
                title,
                pm.usageType,
                pm.createdAt
        );
    }

    /**
     * 从标题映射中选择标题
     * 优先选择简体中文标题
     * @param titles 标题映射
     * @return 选中的标题
     */
    private String pickTitle(Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) {
            return "";
        }
        if (titles.containsKey("zh-cn")) {
            return titles.get("zh-cn");
        }
        return titles.values().stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("");
    }

    /**
     * 查找上传者的用户名
     * @param userId 用户 ID
     * @return 用户名
     */
    private String findUploaderName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = User.findById(userId);
        return user == null ? null : user.username;
    }

    /**
     * 生成图像变体（预览图和缩略图）
     * @param source 原始图像
     * @param storageKey 存储键
     * @param metadata 元数据映射
     */
    private void generateImageVariants(BufferedImage source, String storageKey, Map<String, Object> metadata) {
        String baseName = storageKey;
        int idx = storageKey.lastIndexOf('.');
        if (idx > 0) {
            baseName = storageKey.substring(0, idx);
        }

        String previewKey = baseName + "_preview.jpg";
        String thumbKey = baseName + "_thumb.jpg";
        Path previewPath = uploadRoot.resolve(previewKey);
        Path thumbPath = uploadRoot.resolve(thumbKey);
        try {
            // 生成预览图（最大 48px，质量 0.35）和缩略图（最大 360px，质量 0.82）
            writeJpegVariant(source, previewPath, 48, 0.35f);
            writeJpegVariant(source, thumbPath, 360, 0.82f);
            metadata.put("previewUrl", buildPublicUrl(previewKey));
            metadata.put("thumbnailUrl", buildPublicUrl(thumbKey));
        } catch (IOException e) {
            // 生成失败时清理临时文件
            deleteTarget(previewPath);
            deleteTarget(thumbPath);
        }
    }

    /**
     * 写入 JPEG 格式的变体图像
     * @param source 源图像
     * @param target 目标路径
     * @param maxEdge 最大边长
     * @param quality 压缩质量（0.0-1.0）
     * @throws IOException IO 异常
     */
    private void writeJpegVariant(BufferedImage source, Path target, int maxEdge, float quality) throws IOException {
        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("invalid image dimensions");
        }
        // 计算缩放比例，保持宽高比
        double scale = Math.min(1.0d, Math.min((double) maxEdge / srcWidth, (double) maxEdge / srcHeight));
        int dstWidth = Math.max(1, (int) Math.round(srcWidth * scale));
        int dstHeight = Math.max(1, (int) Math.round(srcHeight * scale));

        // 创建 RGB 图像，白色背景
        BufferedImage output = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            // 设置高质量渲染提示
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dstWidth, dstHeight);
            g.drawImage(source, 0, 0, dstWidth, dstHeight, null);
        } finally {
            g.dispose();
        }

        // 使用 ImageWriter 写入 JPEG，控制压缩质量
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").hasNext()
                ? ImageIO.getImageWritersByFormatName("jpg").next()
                : null;
        if (writer == null) {
            throw new IOException("no jpeg writer available");
        }
        try (OutputStream os = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             ImageOutputStream ios = ImageIO.createImageOutputStream(os)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.05f, Math.min(1.0f, quality)));
            }
            writer.write(null, new IIOImage(output, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    /**
     * 从元数据中获取字符串值
     * @param media 媒体对象
     * @param key 元数据键
     * @return 元数据字符串值
     */
    private String metadataString(Media media, String key) {
        if (media.metadata == null || key == null) {
            return null;
        }
        Object value = media.metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 判断是否需要手动加载原图
     * 当文件大小超过阈值且为常见媒体类型时，需要手动加载
     * @param media 媒体对象
     * @return 是否需要手动加载
     */
    private boolean requiresManualOriginal(Media media) {
        if (media == null || media.size == null || media.size <= MANUAL_LOAD_THRESHOLD_BYTES) {
            return false;
        }
        if (media.mimeType == null) {
            return false;
        }
        String mime = media.mimeType.toLowerCase();
        return mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/");
    }

    /**
     * 验证文件扩展名是否被允许
     * @param fileName 文件名
     * @throws BadRequestException 如果扩展名被禁止
     */
    private void validateExtension(String fileName) {
        String extension = extractExtension(fileName);
        if (!extension.isBlank() && BANNED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new BadRequestException("禁止上传该类型文件");
        }
    }

    /**
     * 提取文件扩展名
     * @param fileName 文件名
     * @return 文件扩展名（包含点号）
     */
    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return fileName.substring(idx).toLowerCase();
    }

    /**
     * 清理文件名，移除路径信息和特殊字符
     * 防止路径遍历攻击和文件名注入
     * @param name 原始文件名
     * @return 清理后的文件名
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String candidate = name.trim();
        int len = candidate.length();
        // 移除末尾的引号字符
        while (len > 0 && (candidate.charAt(len - 1) == '"' || candidate.charAt(len - 1) == '\'')) {
            len--;
        }
        candidate = candidate.substring(0, len);
        // 移除路径分隔符，只保留文件名
        int idx = candidate.lastIndexOf('/');
        if (idx >= 0) {
            candidate = candidate.substring(idx + 1);
        }
        idx = candidate.lastIndexOf('\\');
        if (idx >= 0) {
            candidate = candidate.substring(idx + 1);
        }
        if (candidate.isBlank()) {
            return "file";
        }
        return candidate;
    }

    /**
     * 解析媒体类型
     * @param mimeType MIME 类型
     * @return 媒体类型代码（0=图片，1=视频，2=其他）
     */
    private short parseMediaType(String mimeType) {
        if (mimeType == null) {
            return 2;
        }
        if (mimeType.startsWith("image/")) {
            return 0;
        }
        if (mimeType.startsWith("video/")) {
            return 1;
        }
        return 2;
    }

    /**
     * 规范化公共路径，移除末尾斜杠
     * @param path 原始路径
     * @return 规范化后的路径
     */
    private String normalizePublicPath(String path) {
        if (path == null || path.isBlank()) {
            return "/uploads";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * 删除目标文件（如果存在）
     * @param target 文件路径
     */
    private void deleteTarget(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }

    /**
     * 根据 URL 查找媒体记录
     */
    public Media findByUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        // 1. 尝试直接通过 URL 匹配
        Media media = Media.find("url = ?1 and deletedAt is null", url).firstResult();
        if (media != null) {
            return media;
        }

        // 2. 归一化路径后尝试查找
        String pathKey = normalizeUrlToPath(url);
        if (pathKey.isEmpty()) {
            return null;
        }

        // 遍历所有媒体进行模糊路径匹配（如果媒体数量特别大，此步可能需要优化，但通常 findByUrl 调用频率较低）
        List<Media> medias = Media.list("deletedAt is null");
        for (Media m : medias) {
            if (getMediaIdentityKeys(m).contains(pathKey)) {
                return m;
            }
        }

        return null;
    }

    /**
     * 获取媒体的唯一识别特征集合
     */
    private Set<String> getMediaIdentityKeys(Media media) {
        Set<String> keys = new HashSet<>();
        if (media.url != null) {
            keys.add(normalizeUrlToPath(media.url));
        }
        if (media.storageKey != null) {
            keys.add(media.storageKey.toLowerCase());
        }
        if (media.fileName != null) {
            keys.add(media.fileName.toLowerCase());
        }
        keys.remove("");
        return keys;
    }

    /**
     * 从内容中提取归一化的特征 Key 集合
     */
    private Set<String> extractReferenceKeys(String content) {
        Set<String> keys = new HashSet<>();
        if (content == null || content.isBlank()) {
            return keys;
        }

        Matcher matcher = URL_PATTERN.matcher(content);
        while (matcher.find()) {
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String rawUrl = matcher.group(i);
                if (rawUrl != null && !rawUrl.isBlank()) {
                    keys.add(normalizeUrlToPath(rawUrl));
                }
            }
        }

        return keys;
    }

    /**
     * 归一化 URL/路径：解码、移除协议域名、转小写、移除前导斜杠
     */
    private String normalizeUrlToPath(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            // 1. URL 解码
            String decoded = URLDecoder.decode(rawUrl, StandardCharsets.UTF_8);

            // 2. 剥离协议和域名
            if (decoded.contains("://")) {
                try {
                    decoded = new URI(decoded).getPath();
                } catch (Exception e) {
                    int slashIdx = decoded.indexOf("/", decoded.indexOf("://") + 3);
                    if (slashIdx != -1) {
                        decoded = decoded.substring(slashIdx);
                    }
                }
            }

            // 3. 移除查询参数
            int queryIdx = decoded.indexOf("?");
            if (queryIdx != -1) {
                decoded = decoded.substring(0, queryIdx);
            }

            // 4. 标准化格式
            String path = decoded.toLowerCase().trim();
            while (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        } catch (Exception e) {
            return rawUrl.toLowerCase().trim();
        }
    }
}
