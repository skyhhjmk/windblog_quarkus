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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class MediaManagementService {

    private static final Set<String> BANNED_EXTENSIONS = Set.of(
            ".exe", ".dll", ".cmd", ".bat", ".ps1", ".com", ".class", ".sh", ".jar", ".py"
    );
    private static final long MANUAL_LOAD_THRESHOLD_BYTES = 5L * 1024L * 1024L;

    @ConfigProperty(name = "media.upload.dir")
    String mediaUploadDir;

    @ConfigProperty(name = "media.upload.path")
    String mediaUploadPath;

    @Inject
    UploadRoleService uploadRoleService;

    private Path uploadRoot;
    private String normalizedPublicPath;

    @PostConstruct
    void init() {
        uploadRoot = Paths.get(mediaUploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadRoot);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建媒体存储目录：" + uploadRoot, e);
        }
        normalizedPublicPath = normalizePublicPath(mediaUploadPath);
    }

    @Transactional
    public AdminMediaDtos.MediaListResult listMedia(int page, int pageSize, boolean unreferencedOnly) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        String where = "deletedAt is null";
        if (unreferencedOnly) {
            where += " and id not in (select pm.media.id from PostMedia pm)";
        }
        var query = Media.find(where + " order by createdAt desc");
        long total = query.count();
        List<Media> medias = query.page(Page.of(safePage - 1, safeSize)).list();
        List<Long> ids = medias.stream().map(m -> m.id).collect(Collectors.toList());
        Map<Long, List<PostMedia>> referencesByMedia;
        if (!ids.isEmpty()) {
            List<PostMedia> references = PostMedia.list("media.id in ?1", ids);
            referencesByMedia = references.stream()
                    .collect(Collectors.groupingBy(pm -> pm.media.id));
        } else {
            referencesByMedia = new HashMap<>();
        }
        List<AdminMediaDtos.MediaItem> items = medias.stream()
                .map(media -> toDto(media, referencesByMedia.getOrDefault(media.id, Collections.emptyList())))
                .collect(Collectors.toList());
        return new AdminMediaDtos.MediaListResult(items, total, safePage, safeSize);
    }

    @Transactional
    public AdminMediaDtos.MediaScanResult rebuildReferences() {
        PostMedia.deleteAll();
        List<Post> posts = Post.list("deletedAt is null");
        List<Media> medias = Media.list("deletedAt is null");
        long postsScanned = 0;
        long referencesCreated = 0;
        Set<Long> referenced = new HashSet<>();
        for (Post post : posts) {
            PostRevision revision = post.currentRevision;
            if (revision == null || revision.contentMarkdown == null || revision.contentMarkdown.isEmpty()) {
                continue;
            }
            postsScanned++;
            String normalizedContent = normalizeContent(revision.contentMarkdown);
            for (Media media : medias) {
                if (containsReference(normalizedContent, media)) {
                    persistReference(post, media);
                    referencesCreated++;
                    referenced.add(media.id);
                }
            }
        }
        long unreferenced = medias.size() - referenced.size();
        return new AdminMediaDtos.MediaScanResult(postsScanned, referencesCreated, Math.max(0, unreferenced));
    }

    @Transactional
    public void syncPostReferences(Post post, Map<String, String> contentMap) {
        if (post == null || post.id == null) {
            return;
        }
        PostMedia.delete("post.id = ?1", post.id);
        if (contentMap == null || contentMap.isEmpty()) {
            return;
        }
        List<Media> medias = Media.list("deletedAt is null");
        if (medias.isEmpty()) {
            return;
        }
        String normalizedContent = normalizeContent(contentMap);
        for (Media media : medias) {
            if (containsReference(normalizedContent, media)) {
                persistReference(post, media);
            }
        }
    }

    @Transactional
    public Media storeUploadedMedia(User operator, InputStream source, String fileName, String mimeType, long declaredSize) {
        if (operator == null || operator.id == null) {
            throw new ForbiddenException("上传操作需要有效的用户身份");
        }
        String sanitizedFileName = sanitizeFileName(fileName);
        validateExtension(sanitizedFileName);
        String roleName = operator.roleName;
        if (roleName == null || roleName.isBlank()) {
            roleName = RoleConstant.USER;
        }
        UploadRole role = uploadRoleService.findByName(roleName);
        if (role == null) {
            role = uploadRoleService.findByName(RoleConstant.USER);
        }
        if (role == null || !role.canUpload) {
            throw new ForbiddenException("当前角色不允许上传媒体");
        }
        String normalizedMime = (mimeType == null || mimeType.isBlank()) ? "" : mimeType.trim().toLowerCase();
        if (!role.allowedMimeTypes.isEmpty() && !role.allowedMimeTypes.contains(normalizedMime)) {
            throw new ForbiddenException("当前角色不允许上传该类型文件");
        }
        long currentUsage = sumUsage(operator.id);

        String extension = extractExtension(sanitizedFileName);
        String storageKey = UUID.randomUUID().toString() + extension;
        Path target = uploadRoot.resolve(storageKey);
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

        if (role.maxSingleUploadBytes != null && role.maxSingleUploadBytes > 0 && size > role.maxSingleUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("单文件大小超出限制");
        }
        if (role.maxTotalUploadBytes != null && role.maxTotalUploadBytes > 0
                && currentUsage + size > role.maxTotalUploadBytes) {
            deleteTarget(target);
            throw new BadRequestException("总上传大小超出限制");
        }

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

    private String buildPublicUrl(String storageKey) {
        if (normalizedPublicPath.endsWith("/")) {
            return normalizedPublicPath + storageKey;
        }
        return normalizedPublicPath + "/" + storageKey;
    }

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

    private String normalizeContent(Map<String, String> content) {
        return content.values().stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .collect(Collectors.joining("\n"));
    }

    private boolean containsReference(String normalizedContent, Media media) {
        if (media == null || normalizedContent.isBlank()) {
            return false;
        }
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
                        .collect(Collectors.toList())
        );
    }

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

    private String findUploaderName(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = User.findById(userId);
        return user == null ? null : user.username;
    }

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
            writeJpegVariant(source, previewPath, 48, 0.35f);
            writeJpegVariant(source, thumbPath, 360, 0.82f);
            metadata.put("previewUrl", buildPublicUrl(previewKey));
            metadata.put("thumbnailUrl", buildPublicUrl(thumbKey));
        } catch (IOException e) {
            deleteTarget(previewPath);
            deleteTarget(thumbPath);
        }
    }

    private void writeJpegVariant(BufferedImage source, Path target, int maxEdge, float quality) throws IOException {
        int srcWidth = source.getWidth();
        int srcHeight = source.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("invalid image dimensions");
        }
        double scale = Math.min(1.0d, Math.min((double) maxEdge / srcWidth, (double) maxEdge / srcHeight));
        int dstWidth = Math.max(1, (int) Math.round(srcWidth * scale));
        int dstHeight = Math.max(1, (int) Math.round(srcHeight * scale));

        BufferedImage output = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dstWidth, dstHeight);
            g.drawImage(source, 0, 0, dstWidth, dstHeight, null);
        } finally {
            g.dispose();
        }

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

    private void validateExtension(String fileName) {
        String extension = extractExtension(fileName);
        if (!extension.isBlank() && BANNED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new BadRequestException("禁止上传该类型文件");
        }
    }

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

    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        String candidate = name.trim();
        int len = candidate.length();
        while (len > 0 && (candidate.charAt(len - 1) == '"' || candidate.charAt(len - 1) == '\'')) {
            len--;
        }
        candidate = candidate.substring(0, len);
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

    private String normalizePublicPath(String path) {
        if (path == null || path.isBlank()) {
            return "/uploads";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void deleteTarget(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }
}
