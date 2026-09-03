package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.helper.MediaPathHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.controller.api.admin.dto.AdminMediaDtos;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.edge.EdgeWriteGuard;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Page;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Set<String> CODEX_IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    // 识别 Markdown 和 HTML 中 URL 的正则表达式
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:src|href|url|data-src)=['\"]([^'\"\\s>]+)['\"]|!\\[.*?\\]\\(([^)\\s]+)\\)|\\[.*?\\]\\(([^)\\s]+)\\)"
    );

    @ConfigProperty(name = "media.upload.dir")
    String mediaUploadDir;

    @ConfigProperty(name = "media.upload.path")
    String mediaUploadPath;

    @ConfigProperty(name = "windblog.media.processing.async", defaultValue = "true")
    boolean asyncMediaProcessing;

    @Inject
    UploadRoleService uploadRoleService;

    @Inject
    ImageProcessingService imageProcessingService;

    @Inject
    VideoProcessingService videoProcessingService;

    @Inject
    StorageService storageService;

    @Inject
    com.biliwind.blog.service.security.SafeExternalHttpService safeExternalHttpService;

    @Inject
    MediaVirusScanService mediaVirusScanService;

    @Inject
    Instance<MediaManagementService> selfProxy;

    @Inject
    Instance<OutboxEventService> outboxEventService;

    @Inject
    EdgeWriteGuard edgeWriteGuard;

    @Inject
    PostAccessService postAccessService;

    private Path uploadRoot;
    private String normalizedPublicPath;

    /**
     * 批量重试导入失败的媒体
     * 查询所有 metadata 中 importStatus="failed" 的媒体并逐个重试
     * @return 批量重试结果
     */
    @Transactional
    public AdminMediaDtos.BatchRetryResult batchRetryFailedImports() {
        String failedWhere = "deleted_at is null and metadata->>'importStatus' = 'failed'";
        int totalCount = ((Number) Media.getEntityManager()
                .createNativeQuery("select count(*) from media where " + failedWhere)
                .getSingleResult()).intValue();
        int successCount = 0;
        int failedCount = 0;
        List<AdminMediaDtos.BatchRetryItemResult> results = new ArrayList<>();
        long lastMediaId = 0L;
        while (true) {
            List<Media> failedMedias = Media.getEntityManager()
                    .createNativeQuery("select * from media where " + failedWhere
                            + " and id > :lastMediaId order by id", Media.class)
                    .setParameter("lastMediaId", lastMediaId)
                    .setMaxResults(200)
                    .getResultList();
            if (failedMedias.isEmpty()) {
                break;
            }
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
                    errorMessage = SensitiveMessageSanitizer.sanitize(e.getMessage());
                } catch (Exception e) {
                    failedCount++;
                    errorMessage = SensitiveMessageSanitizer.sanitize(e.getMessage());
                }
                AdminMediaDtos.BatchRetryItemResult itemResult = new AdminMediaDtos.BatchRetryItemResult(
                        mediaId,
                        fileName,
                        success,
                        errorMessage);
                results.add(itemResult);
                lastMediaId = mediaId;
            }
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
            io.quarkus.hibernate.orm.panache.PanacheQuery<Media> query = Media.find(where + " order by createdAt desc");
            total = query.count();
            medias = query.page(Page.of(safePage - 1, safeSize)).list();
        }

        List<Long> ids = new ArrayList<>();
        for (Media media : medias) {
            ids.add(media.id);
        }
        Map<Long, List<PostMedia>> referencesByMedia;
        if (!ids.isEmpty()) {
            List<PostMedia> references = PostMedia.list("media.id in ?1", ids);
            referencesByMedia = new HashMap<>();
            for (PostMedia pm : references) {
                Long mediaId = pm.media.id;
                List<PostMedia> list = referencesByMedia.get(mediaId);
                if (list == null) {
                    list = new ArrayList<>();
                    referencesByMedia.put(mediaId, list);
                }
                list.add(pm);
            }
        } else {
            referencesByMedia = new HashMap<>();
        }
        Map<Long, String> uploaderNames = loadUploaderNames(medias);
        List<AdminMediaDtos.MediaItem> items = new ArrayList<>();
        for (Media media : medias) {
            List<PostMedia> refs = referencesByMedia.get(media.id);
            if (refs == null) {
                refs = Collections.emptyList();
            }
            AdminMediaDtos.MediaItem item = toDto(media, refs, uploaderNames);
            items.add(item);
        }
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void clearAllMediaReferences() {
        PostMedia.deleteAll();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public ReferenceBatchResult rebuildReferenceBatch(Long afterPostId, int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 100));
        List<Media> medias = Media.list("deletedAt is null");
        Map<String, List<Media>> mediaByPathKey = new HashMap<>();
        for (Media media : medias) {
            for (String key : getMediaIdentityKeys(media)) {
                mediaByPathKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(media);
            }
        }

        Long cursor = afterPostId == null ? 0L : afterPostId;
        List<Post> posts = Post.find("deletedAt is null and id > ?1 order by id", cursor)
                .page(Page.of(0, batchSize)).list();
        if (posts.isEmpty()) {
            return new ReferenceBatchResult(afterPostId, 0, 0, false);
        }

        List<Long> postIds = new ArrayList<>();
        for (Post post : posts) {
            postIds.add(post.id);
        }
        PostMedia.delete("post.id in ?1", postIds);

        long postsScanned = 0;
        long referencesCreated = 0;
        for (Post post : posts) {
            PostRevision revision = post.currentRevision;
            if (revision == null || revision.contentMarkdown == null || revision.contentMarkdown.isEmpty()) {
                continue;
            }
            postsScanned++;
            Set<Long> matchedInPost = new HashSet<>();
            for (String key : extractReferenceKeys(normalizeContent(revision.contentMarkdown))) {
                List<Media> matches = mediaByPathKey.get(key);
                if (matches == null) {
                    continue;
                }
                for (Media media : matches) {
                    if (matchedInPost.add(media.id)) {
                        persistReference(post, media);
                        referencesCreated++;
                    }
                }
            }
        }
        Long lastPostId = posts.get(posts.size() - 1).id;
        return new ReferenceBatchResult(lastPostId, postsScanned, referencesCreated, posts.size() == batchSize);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public long countUnreferencedMedia() {
        return ((Number) Media.getEntityManager().createNativeQuery(
                "select count(*) from media where deleted_at is null "
                        + "and id not in (select media_id from post_media)").getSingleResult()).longValue();
    }

    public record ReferenceBatchResult(Long lastPostId, long postsScanned,
                                       long referencesCreated, boolean hasMore) {
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
    public Media storeUploadedMedia(User operator, InputStream source, String fileName, String mimeType, long declaredSize) {
        edgeWriteGuard.rejectWriteOnEdge("保存上传媒体");
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

        return storeUploadedMediaInternal(source, sanitizedFileName, normalizedMime, operator.id,
                role.maxSingleUploadBytes, currentUsage, role.maxTotalUploadBytes, new HashMap<>());
    }

    /**
     * Stores an image through the private Codex Creator integration boundary.
     * This path deliberately has no browser user identity and does not accept
     * caller metadata; the AI marker is supplied by the WindBlog boundary.
     */
    public Media storeCodexUploadedImage(InputStream source, String fileName, String mimeType,
                                         long declaredSize, long maxBytes,
                                         Map<String, Object> forcedMetadata) {
        edgeWriteGuard.rejectWriteOnEdge("保存 Codex AI 图片");
        if (source == null) {
            throw new BadRequestException("图片内容不能为空");
        }
        if (maxBytes <= 0) {
            throw new IllegalStateException("Codex 图片大小限制未配置");
        }
        String sanitizedFileName = sanitizeFileName(fileName);
        validateExtension(sanitizedFileName);
        String normalizedMime = (mimeType == null || mimeType.isBlank())
                ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!CODEX_IMAGE_MIME_TYPES.contains(normalizedMime)) {
            throw new BadRequestException("Codex 只允许上传 PNG、JPEG、GIF 或 WebP 图片");
        }
        if (declaredSize <= 0 || declaredSize > maxBytes) {
            throw new BadRequestException("图片大小超出 Codex 上传限制");
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (forcedMetadata != null) {
            metadata.putAll(forcedMetadata);
        }
        // These values are authoritative and cannot be removed or replaced by
        // an integration caller. Ordinary browser uploads use the other path
        // and therefore remain unmarked.
        metadata.put("aiUploaded", true);
        metadata.put("uploadSource", "CODEX_CREATOR");
        metadata.put("generationMethod", "CODEX_APP_SERVER");
        return storeUploadedMediaInternal(source, sanitizedFileName, normalizedMime, null,
                maxBytes, 0L, null, metadata);
    }

    private Media storeUploadedMediaInternal(InputStream source, String sanitizedFileName,
                                             String normalizedMime, Long operatorId,
                                             Long maxSingleUploadBytes, long currentUsage,
                                             Long maxTotalUploadBytes,
                                             Map<String, Object> metadata) {

        String extension = extractExtension(sanitizedFileName);
        // 使用 UUID 生成唯一的存储键，防止文件名冲突
        String storageKey = UUID.randomUUID().toString() + extension;
        Path target = uploadRoot.resolve(storageKey);
        // 写入文件到磁盘
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            copySourceWithinLimit(source, output, maxSingleUploadBytes);
        } catch (IOException e) {
            deleteTarget(target);
            throw new IllegalStateException("写入媒体文件失败", e);
        }

        // 验证文件头 (Magic Number) 以防止伪装文件
        try (InputStream checkStream = Files.newInputStream(target)) {
            byte[] head = new byte[16];
            int readCount = checkStream.read(head);
            if (readCount <= 0
                    || !com.biliwind.blog.common.helper.MediaSecurityHelper.validateMagicNumber(head, normalizedMime)) {
                deleteTarget(target);
                throw new BadRequestException("文件内容与声明的类型不符（Magic Number 校验失败）");
            }
        } catch (IOException e) {
            deleteTarget(target);
            throw new IllegalStateException("无法完成媒体类型校验", e);
        }

        MediaVirusScanService.ScanResult virusScanResult = mediaVirusScanService.scan(target);
        if (!virusScanResult.isClean()) {
            deleteTarget(target);
            if (virusScanResult.status() == MediaVirusScanService.Status.INFECTED) {
                throw new BadRequestException("媒体文件未通过病毒扫描");
            }
            if (virusScanResult.status() == MediaVirusScanService.Status.UNAVAILABLE) {
                throw new ServiceUnavailableException(
                        "病毒扫描服务暂不可用，上传未完成：" + virusScanResult.reason());
            }
            throw new IllegalStateException("媒体文件未完成病毒扫描：" + virusScanResult.reason());
        }

        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            deleteTarget(target);
            throw new IllegalStateException("无法获取文件大小", e);
        }

        // 验证单文件大小限制
        if (maxSingleUploadBytes != null && maxSingleUploadBytes > 0 && size > maxSingleUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("单文件大小超出限制");
        }
        // 验证总上传大小限制
        if (maxTotalUploadBytes != null && maxTotalUploadBytes > 0
                && currentUsage + size > maxTotalUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("总上传大小超出限制");
        }

        MediaManagementService transactionalSelf = selfProxy.get();
        Media media = transactionalSelf.createPendingUploadedMedia(
                storageKey, normalizedMime, sanitizedFileName, size, operatorId, metadata,
                toVirusScanStatus(virusScanResult.status()), OffsetDateTime.now(),
                sanitizeVirusScanMessage(virusScanResult));

        if (asyncMediaProcessing) {
            try {
                outboxEventService.get().enqueue(
                        "MEDIA_PROCESS:" + media.id,
                        "MEDIA_PROCESS",
                        "MEDIA",
                        media.id.toString(),
                        Map.of("mediaId", media.id),
                        null);
                return media;
            } catch (Exception exception) {
                transactionalSelf.updateProcessingStatus(media.id, "FAILED", 0,
                        "媒体处理任务入队失败: "
                                + SensitiveMessageSanitizer.sanitize(exception.getMessage()));
                throw new IllegalStateException("媒体处理任务入队失败", exception);
            }
        }

        return processPendingMedia(media.id);
    }

    /**
     * Scans one existing media original and stores the result for the media library.
     * The file is read from the local original first, then from configured storage copies.
     */
    public Media scanVirus(Long mediaId) {
        VirusScanClaim claim = selfProxy.get().claimVirusScan(mediaId);
        if (claim == null) {
            return null;
        }
        if (!claim.claimed()) {
            return claim.media();
        }

        MediaVirusScanService.ScanResult result;
        try (InputStream input = openOriginalForVirusScan(claim.media())) {
            result = mediaVirusScanService.scan(input);
        } catch (Exception exception) {
            result = new MediaVirusScanService.ScanResult(
                    MediaVirusScanService.Status.UNAVAILABLE, "媒体原始文件无法读取");
        }
        return selfProxy.get().completeVirusScan(mediaId, result);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public VirusScanClaim claimVirusScan(Long mediaId) {
        if (mediaId == null) {
            return null;
        }
        Media media = Media.find("id = ?1 and deletedAt is null", mediaId)
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResult();
        if (media == null) {
            return null;
        }
        if ("SCANNING".equals(media.virusScanStatus)) {
            return new VirusScanClaim(media, false);
        }
        media.virusScanStatus = "SCANNING";
        media.virusScanMessage = "正在扫描";
        media.persist();
        return new VirusScanClaim(media, true);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Media completeVirusScan(Long mediaId, MediaVirusScanService.ScanResult result) {
        Media media = Media.findById(mediaId);
        if (media == null || media.deletedAt != null) {
            return null;
        }
        MediaVirusScanService.ScanResult safeResult = result == null
                ? new MediaVirusScanService.ScanResult(
                        MediaVirusScanService.Status.UNAVAILABLE, "病毒扫描未返回结果")
                : result;
        media.virusScanStatus = toVirusScanStatus(safeResult.status());
        media.virusScannedAt = OffsetDateTime.now();
        media.virusScanMessage = sanitizeVirusScanMessage(safeResult);
        media.persist();
        return media;
    }

    private InputStream openOriginalForVirusScan(Media media) throws IOException {
        if (media != null && media.storageKey != null && !media.storageKey.isBlank()) {
            Path localTarget = uploadRoot.resolve(media.storageKey).normalize();
            if (localTarget.startsWith(uploadRoot) && Files.isRegularFile(localTarget)) {
                return Files.newInputStream(localTarget);
            }
        }
        try {
            return storageService.fallbackDownload(media, VariantType.ORIGINAL);
        } catch (Exception exception) {
            throw new IOException("媒体原始文件不存在或无法从存储读取", exception);
        }
    }

    private String toVirusScanStatus(MediaVirusScanService.Status status) {
        if (status == null) {
            return "UNAVAILABLE";
        }
        return status.name();
    }

    private String sanitizeVirusScanMessage(MediaVirusScanService.ScanResult result) {
        if (result == null || result.reason() == null || result.reason().isBlank()) {
            return null;
        }
        return SensitiveMessageSanitizer.sanitize(result.reason());
    }

    /** Processes one durable media job; the caller is the outbox worker, not an HTTP request. */
    public Media processPendingMedia(Long mediaId) {
        ProcessingClaim claim = selfProxy.get().claimMediaProcessing(mediaId);
        if (claim == null) {
            return null;
        }

        Path target = uploadRoot.resolve(claim.storageKey()).normalize();
        if (!target.startsWith(uploadRoot) || !Files.isRegularFile(target)) {
            selfProxy.get().updateProcessingStatus(mediaId, "FAILED", 0, "媒体源文件不存在或路径无效");
            throw new IllegalStateException("媒体源文件不存在或路径无效: " + claim.storageKey());
        }

        Media detachedMedia = new Media();
        detachedMedia.id = claim.mediaId();
        detachedMedia.storageKey = claim.storageKey();
        Map<String, Object> metadata = claim.metadata() == null
                ? new HashMap<>() : new HashMap<>(claim.metadata());
        try {
            if (claim.mimeType() != null && claim.mimeType().startsWith("image/")) {
                processImage(detachedMedia, target, metadata);
                selfProxy.get().updateProcessingStatus(mediaId, "PROCESSING", 60, null);
            }
            if (claim.mimeType() != null && claim.mimeType().startsWith("video/")) {
                processVideo(detachedMedia, target, claim.storageKey(), metadata);
                selfProxy.get().updateProcessingStatus(mediaId, "PROCESSING", 60, null);
            }

            Map<VariantType, String> generatedVariants = new HashMap<>();
            generatedVariants.put(VariantType.ORIGINAL, claim.storageKey());
            if (metadata.containsKey("webpUrl")) {
                generatedVariants.put(VariantType.WEBP, extractStorageKey((String) metadata.get("webpUrl")));
            }
            if (metadata.containsKey("placeholderUrl")) {
                generatedVariants.put(VariantType.PLACEHOLDER,
                        extractStorageKey((String) metadata.get("placeholderUrl")));
            }
            if (metadata.containsKey("coverUrl")) {
                generatedVariants.put(VariantType.COVER,
                        extractStorageKey((String) metadata.get("coverUrl")));
            }
            return selfProxy.get().completeUploadedMedia(
                    mediaId, detachedMedia.width, detachedMedia.height, metadata, generatedVariants);
        } catch (Exception exception) {
            Log.error("媒体处理失败: " + mediaId, exception);
            selfProxy.get().updateProcessingStatus(mediaId, "FAILED", 0,
                    SensitiveMessageSanitizer.sanitize(exception.getMessage()));
            throw new IllegalStateException("媒体处理失败: " + mediaId, exception);
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public ProcessingClaim claimMediaProcessing(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null || media.deletedAt != null) {
            return null;
        }
        if ("COMPLETED".equals(media.processingStatus)) {
            return null;
        }
        if ("PROCESSING".equals(media.processingStatus)
                && media.updatedAt != null
                && media.updatedAt.isAfter(OffsetDateTime.now().minusMinutes(15))) {
            return null;
        }
        media.processingStatus = "PROCESSING";
        media.processingProgress = 10;
        media.processingError = null;
        media.persist();
        return new ProcessingClaim(media.id, media.storageKey, media.mimeType, media.metadata);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Media createPendingUploadedMedia(String storageKey, String normalizedMime, String sanitizedFileName,
                                            long size, Long operatorId, Map<String, Object> metadata) {
        return createPendingUploadedMedia(storageKey, normalizedMime, sanitizedFileName, size, operatorId,
                metadata, "NOT_SCANNED", null, null);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Media createPendingUploadedMedia(String storageKey, String normalizedMime, String sanitizedFileName,
                                            long size, Long operatorId, Map<String, Object> metadata,
                                            String virusScanStatus, OffsetDateTime virusScannedAt,
                                            String virusScanMessage) {
        Media media = new Media();
        media.storageKey = storageKey;
        media.url = buildPublicUrl(storageKey);
        media.mediaType = parseMediaType(normalizedMime);
        media.mimeType = normalizedMime;
        media.fileName = sanitizedFileName;
        media.size = size;
        media.uploadedBy = operatorId;
        media.width = null;
        media.height = null;
        media.alt = Collections.emptyMap();
        media.metadata = metadata;
        media.createdAt = OffsetDateTime.now();
        media.deletedAt = null;
        media.version = 0;
        media.storageClasses = new LinkedHashMap<>();
        media.processingStatus = "PENDING";
        media.processingProgress = 0;
        media.virusScanStatus = virusScanStatus == null || virusScanStatus.isBlank()
                ? "NOT_SCANNED" : virusScanStatus;
        media.virusScannedAt = virusScannedAt;
        media.virusScanMessage = virusScanMessage;
        media.persist();
        Media.getEntityManager().flush();
        return media;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Media completeUploadedMedia(Long mediaId, Integer width, Integer height, Map<String, Object> metadata,
                                       Map<VariantType, String> generatedVariants) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            throw new IllegalStateException("媒体记录不存在: " + mediaId);
        }
        media.width = width;
        media.height = height;
        media.metadata = metadata;
        media.storageClasses = new LinkedHashMap<>();

        Map<String, Object> primaryProviderJson = new LinkedHashMap<>();
        for (Map.Entry<VariantType, String> entry : generatedVariants.entrySet()) {
            VariantType variant = entry.getKey();
            String variantStorageKey = entry.getValue();
            Map<String, Object> variantInfo = new LinkedHashMap<>();
            variantInfo.put("status", "synced");
            variantInfo.put("path", variantStorageKey);
            primaryProviderJson.put(variant.name().toLowerCase(), variantInfo);
        }
        media.storageClasses.put(storageService.getPrimaryStorageClassName(), primaryProviderJson);

        for (StorageClassEntity nonPrimary : storageService.getNonPrimaryStorageClassEntities()) {
            Map<String, Object> pendingProviderJson = new LinkedHashMap<>();
            for (VariantType variant : generatedVariants.keySet()) {
                Map<String, Object> pendingVariant = new LinkedHashMap<>();
                pendingVariant.put("status", "pending");
                pendingVariant.put("path", null);
                pendingProviderJson.put(variant.name().toLowerCase(), pendingVariant);
            }
            media.storageClasses.put(nonPrimary.name, pendingProviderJson);
        }

        media.processingStatus = "COMPLETED";
        media.processingProgress = 100;
        media.persist();
        storageService.scheduleSyncForMedia(media.id);
        return media;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateProcessingStatus(Long mediaId, String status, Integer progress, String error) {
        Media media = Media.findById(mediaId);
        if (media != null) {
            media.processingStatus = status;
            media.processingProgress = progress;
            media.processingError = error;
            media.persist();
        }
    }

    public record ProcessingClaim(Long mediaId, String storageKey, String mimeType,
                                  Map<String, Object> metadata) {
    }

    public record VirusScanClaim(Media media, boolean claimed) {
    }

    private void processImage(Media media, Path target, Map<String, Object> metadata) {
        BufferedImage image;
        try {
            image = ImageIO.read(target.toFile());
        } catch (IOException exception) {
            recordProcessingWarning(metadata, "图片", exception);
            Log.warn("图片变体生成失败，保留原图: " + exception.getMessage());
            return;
        }
        if (image == null) {
            IOException exception = new IOException("无法读取原图片: " + target);
            recordProcessingWarning(metadata, "图片", exception);
            Log.warn("图片变体生成失败，保留原图: " + exception.getMessage());
            return;
        }

        media.width = image.getWidth();
        media.height = image.getHeight();
        metadata.put("width", image.getWidth());
        metadata.put("height", image.getHeight());

        String baseName = media.storageKey;
        int idx = media.storageKey.lastIndexOf('.');
        if (idx > 0) {
            baseName = media.storageKey.substring(0, idx);
        }

        String placeholderKey = baseName + "_placeholder.jpg";
        Path placeholderPath = uploadRoot.resolve(placeholderKey);
        try {
            imageProcessingService.generatePlaceholder(target, placeholderPath);
            metadata.put("placeholderUrl", buildPublicUrl(placeholderKey));
        } catch (Exception exception) {
            recordProcessingWarning(metadata, "占位图", exception);
            Log.warn("占位图生成失败，保留原图: " + exception.getMessage());
        }

        String webpKey = baseName + ".webp";
        Path webpPath = uploadRoot.resolve(webpKey);
        try {
            imageProcessingService.convertToWebp(target, webpPath);
            metadata.put("webpUrl", buildPublicUrl(webpKey));
        } catch (Exception exception) {
            recordProcessingWarning(metadata, "WebP", exception);
            Log.warn("WebP 变体生成失败，保留原图: " + exception.getMessage());
        }
    }

    private void recordProcessingWarning(Map<String, Object> metadata, String variantName,
                                         Exception exception) {
        String message = SensitiveMessageSanitizer.sanitize(exception.getMessage());
        if (message == null || message.isBlank()) {
            message = "未知处理错误";
        }
        String warning = variantName + "变体生成失败: " + message;
        Object previousValue = metadata.get("processingWarning");
        if (previousValue instanceof String previous && !previous.isBlank()) {
            warning = previous + "；" + warning;
        }
        metadata.put("processingWarning", warning);
    }

    private void processVideo(Media media, Path target, String storageKey, Map<String, Object> metadata) {
        try {
            String baseName = storageKey;
            int idx = storageKey.lastIndexOf('.');
            if (idx > 0) {
                baseName = storageKey.substring(0, idx);
            }

            String coverKey = baseName + "_cover.jpg";
            Path coverPath = uploadRoot.resolve(coverKey);
            Path coverOutput = videoProcessingService.extractCoverFrame(target, coverPath);
            String coverUrl = buildPublicUrl(coverKey);
            metadata.put("coverUrl", coverUrl);

            int[] dimensions = imageProcessingService.extractDimensions(coverOutput);
            media.width = dimensions[0];
            media.height = dimensions[1];
        } catch (IOException e) {
            Log.warn("视频封面提取失败: " + e.getMessage());
        }
    }

    /**
     * 从 URL 导入外部媒体文件
     *
     * @param operator 操作用户
     * @param fileUrl  外部文件 URL
     * @return 导入成功的媒体对象
     * @throws IOException 下载或存储失败时抛出
     */
    public Media importFromUrl(User operator, String fileUrl) throws IOException {
        return importFromUrl(operator, fileUrl, null);
    }

    public Media importFromUrl(User operator, String fileUrl,
                               com.biliwind.blog.service.security.SafeExternalHttpService.DownloadProgressListener progressListener)
            throws IOException {
        try {
            com.biliwind.blog.service.security.SafeExternalHttpService.ExternalHttpResponse response =
                    safeExternalHttpService.get(fileUrl, "WindBlog-Safe-Media-Importer/1.0",
                            256 * 1024 * 1024, progressListener);
            if (response.statusCode() != 200) {
                throw new IOException("下载文件失败，HTTP 状态码: " + response.statusCode());
            }
            String contentType = response.headerValue("Content-Type");
            if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                throw new IOException("下载内容疑似为 HTML 页面而非媒体文件，已拦截");
            }
            return storeUploadedMedia(operator,
                    new ByteArrayInputStream(response.body()),
                    extractFileNameFromUrl(fileUrl),
                    contentType,
                    response.body().length);
        } catch (IllegalArgumentException exception) {
            throw new IOException("外部媒体地址被安全策略拒绝", exception);
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
        media.url = "";
        media.mediaType = 2; // 其他
        media.fileName = extractFileNameFromUrl(sourceUrl);
        media.size = 0L;
        media.uploadedBy = operator.id;
        media.mimeType = "application/octet-stream";
        media.alt = Collections.emptyMap();
        media.storageClasses = new LinkedHashMap<>();
        media.version = 0;
        media.processingStatus = "FAILED";
        media.processingProgress = 0;
        media.processingError = SensitiveMessageSanitizer.sanitize(error);
        media.deletedAt = null;
        media.createdAt = OffsetDateTime.now();
        media.virusScanStatus = "NOT_SCANNED";

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("importStatus", "failed");
        metadata.put("sourceUrl", sourceUrl);
        metadata.put("importError", SensitiveMessageSanitizer.sanitize(error));
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
    public Media retryImport(Long mediaId) throws IOException {
        RetryImportContext context = selfProxy.get().loadRetryImportContext(mediaId);
        User operator = new User();
        operator.id = context.operatorId();
        operator.roleName = context.operatorRoleName();

        try {
            Media newMedia = importFromUrl(operator, context.sourceUrl());
            return selfProxy.get().applyRetryImportResult(context.mediaId(), context.oldUrl(), newMedia);
        } catch (Exception e) {
            String safeMessage = SensitiveMessageSanitizer.sanitize(e.getMessage());
            selfProxy.get().recordRetryImportFailure(context.mediaId(), safeMessage);
            throw new IOException("重试导入仍然失败: " + safeMessage);
        }
    }

    @Transactional
    public RetryImportContext loadRetryImportContext(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            throw new BadRequestException("媒体不存在");
        }
        Map<String, Object> metadata = media.metadata != null ? new HashMap<>(media.metadata) : new HashMap<>();
        if (!"failed".equals(String.valueOf(metadata.get("importStatus")))) {
            throw new BadRequestException("该媒体并未处于导入失败状态");
        }
        String sourceUrl = String.valueOf(metadata.get("sourceUrl"));
        if (sourceUrl == null || sourceUrl.isBlank() || "null".equals(sourceUrl)) {
            throw new BadRequestException("找不到原始来源 URL");
        }
        User operator = media.uploadedBy == null ? null : User.findById(media.uploadedBy);
        if (operator == null) {
            operator = User.find("roleName = ?1", RoleConstant.SUPER_ADMIN).firstResult();
        }
        if (operator == null || operator.id == null) {
            throw new BadRequestException("找不到媒体导入操作用户");
        }
        return new RetryImportContext(media.id, media.url, sourceUrl, operator.id, operator.roleName);
    }

    @Transactional
    public Media applyRetryImportResult(Long mediaId, String oldUrl, Media newMedia) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            throw new BadRequestException("媒体不存在");
        }
        String newUrl = newMedia.url;
        media.storageKey = newMedia.storageKey;
        media.url = newUrl;
        media.mediaType = newMedia.mediaType;
        media.mimeType = newMedia.mimeType;
        media.fileName = newMedia.fileName;
        media.size = newMedia.size;
        media.width = newMedia.width;
        media.height = newMedia.height;
        media.metadata = newMedia.metadata;
        media.persist();
        if (oldUrl != null && !oldUrl.isBlank() && !oldUrl.equals(newUrl)) {
            updatePostReferences(oldUrl, newUrl);
        }
        return media;
    }

    @Transactional
    public void recordRetryImportFailure(Long mediaId, String error) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            return;
        }
        Map<String, Object> metadata = media.metadata != null ? new HashMap<>(media.metadata) : new HashMap<>();
        metadata.put("importError", SensitiveMessageSanitizer.sanitize(error));
        metadata.put("lastRetryAt", OffsetDateTime.now().toString());
        media.metadata = metadata;
        media.persist();
    }

    public record RetryImportContext(Long mediaId, String oldUrl, String sourceUrl,
                                     Long operatorId, String operatorRoleName) {
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
     * 从 URL 中提取存储键（文件名部分）
     */
    private String extractStorageKey(String url) {
        if (url == null) return null;
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash >= 0) {
            return url.substring(lastSlash + 1);
        }
        return url;
    }

    /**
     * 标准化内容以便引用检查
     * @param content 内容映射
     * @return 标准化后的内容字符串
     */
    private String normalizeContent(Map<String, String> content) {
        StringBuilder sb = new StringBuilder();
        for (String value : content.values()) {
            if (value != null) {
                sb.append(value.toLowerCase());
                sb.append("\n");
            }
        }
        return sb.toString();
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
        return toDto(media, references, loadUploaderNames(List.of(media)));
    }

    private AdminMediaDtos.MediaItem toDto(Media media, List<PostMedia> references,
                                           Map<Long, String> uploaderNames) {
        long referencedBy = 0;
        boolean protectedMedia = false;
        if (references != null) {
            referencedBy = references.size();
            for (PostMedia reference : references) {
                if (postAccessService.isProtectedMediaReference(reference)) {
                    protectedMedia = true;
                    break;
                }
            }
        }
        List<AdminMediaDtos.MediaReferenceItem> refItems;
        if (references == null) {
            refItems = Collections.emptyList();
        } else {
            refItems = new ArrayList<>();
            for (PostMedia pm : references) {
                AdminMediaDtos.MediaReferenceItem item = toReferenceDto(pm);
                refItems.add(item);
            }
        }

        String originalUrl = protectedMedia ? null : media.url;
        if (!protectedMedia && media.storageClasses != null) {
            String bestOriginalUrl = storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
            if (bestOriginalUrl != null && !bestOriginalUrl.isBlank()) {
                originalUrl = bestOriginalUrl;
            }
        }

        String thumbnailUrl = protectedMedia ? null : metadataString(media, "thumbnailUrl");
        if (!protectedMedia && media.storageClasses != null) {
            String bestThumbnailUrl = storageService.getBestAccessUrl(media, VariantType.WEBP);
            if (bestThumbnailUrl != null && !bestThumbnailUrl.isBlank()) {
                thumbnailUrl = bestThumbnailUrl;
            }
        }

        String previewUrl = protectedMedia ? null : metadataString(media, "previewUrl");
        if (!protectedMedia && media.storageClasses != null) {
            String bestPreviewUrl = storageService.getBestAccessUrl(media, VariantType.PLACEHOLDER);
            if (bestPreviewUrl != null && !bestPreviewUrl.isBlank()) {
                previewUrl = bestPreviewUrl;
            }
        }
        Map<String, Object> safeStorageClasses = protectedMedia
                ? null : sanitizeStorageClasses(media.storageClasses);
        Map<String, Object> safeMetadata = protectedMedia
                ? Collections.emptyMap() : sanitizeMediaMetadata(media.metadata);

        return new AdminMediaDtos.MediaItem(
                media.id,
                null,
                originalUrl,
                thumbnailUrl,
                previewUrl,
                requiresManualOriginal(media),
                media.fileName,
                media.mimeType,
                media.size,
                media.mediaType,
                media.width,
                media.height,
                media.uploadedBy,
                uploaderNames.get(media.uploadedBy),
                media.createdAt,
                referencedBy > 0,
                refItems,
                media.visibilityRegions,
                media.hiddenRegions,
                media.syncStorageClasses,
                media.skipStorageClasses,
                safeStorageClasses,
                safeMetadata,
                media.processingStatus == null ? "PENDING" : media.processingStatus,
                media.processingProgress == null ? 0 : media.processingProgress,
                media.processingError == null ? null : SensitiveMessageSanitizer.sanitize(media.processingError),
                media.virusScanStatus == null ? "NOT_SCANNED" : media.virusScanStatus,
                media.virusScannedAt,
                media.virusScanMessage
        );
    }

    private Map<String, Object> sanitizeStorageClasses(Map<String, Object> storageClasses) {
        if (storageClasses == null || storageClasses.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> providerEntry : storageClasses.entrySet()) {
            if (!(providerEntry.getValue() instanceof Map<?, ?> providerValue)) {
                continue;
            }
            Map<String, Object> safeVariants = new LinkedHashMap<>();
            for (Map.Entry<?, ?> variantEntry : providerValue.entrySet()) {
                if (!(variantEntry.getKey() instanceof String variantName)
                        || !(variantEntry.getValue() instanceof Map<?, ?> variantValue)) {
                    continue;
                }
                Map<String, Object> safeVariant = new LinkedHashMap<>();
                copySafeScalar(variantValue, safeVariant, "status");
                copySafeScalar(variantValue, safeVariant, "size");
                safeVariants.put(variantName, safeVariant);
            }
            result.put(providerEntry.getKey(), safeVariants);
        }
        return result;
    }

    private Map<String, Object> sanitizeMediaMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<String> allowedKeys = Set.of(
                "placeholderUrl", "webpUrl", "coverUrl", "width", "height",
                "importStatus", "importError", "lastRetryAt", "processingWarning",
                "aiUploaded", "uploadSource", "generationMethod");
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            if (metadata.containsKey(key)) {
                result.put(key, metadata.get(key));
            }
        }
        return result;
    }

    private void copySafeScalar(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
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
        for (String title : titles.values()) {
            if (title != null) {
                return title;
            }
        }
        return "";
    }

    /**
     * 查找上传者的用户名
     * @param userId 用户 ID
     * @return 用户名
     */
    private Map<Long, String> loadUploaderNames(List<Media> medias) {
        Map<Long, String> uploaderNames = new HashMap<>();
        List<Long> userIds = new ArrayList<>();
        for (Media media : medias) {
            if (media.uploadedBy != null) {
                userIds.add(media.uploadedBy);
            }
        }
        if (userIds.isEmpty()) {
            return uploaderNames;
        }
        List<User> users = User.find("id in ?1", userIds).list();
        for (User user : users) {
            uploaderNames.put(user.id, user.username);
        }
        return uploaderNames;
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
        return MediaPathHelper.normalizePublicPath(path);
    }

    /**
     * 删除目标文件（如果存在）
     * @param target 文件路径
     */
    private void copySourceWithinLimit(InputStream source, OutputStream output, Long maxSingleUploadBytes) throws IOException {
        byte[] buffer = new byte[8192];
        long copiedBytes = 0;
        int readLength = source.read(buffer);
        while (readLength >= 0) {
            copiedBytes = copiedBytes + readLength;
            if (maxSingleUploadBytes != null && maxSingleUploadBytes > 0 && copiedBytes > maxSingleUploadBytes) {
                throw new BadRequestException("单文件大小超出限制");
            }
            output.write(buffer, 0, readLength);
            readLength = source.read(buffer);
        }
    }
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
        return MediaPathHelper.normalizeUrlToPathKey(rawUrl);
    }
}
