package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.UploadRole;
import com.biliwind.blog.model.User;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.ForbiddenException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

@ApplicationScoped
public class MediaChunkUploadService {

    private static final Logger LOG = Logger.getLogger(MediaChunkUploadService.class);
    private static final String META_FILE = "session.properties";
    private static final String CHUNK_PREFIX = "chunk-";

    @Inject
    UploadRoleService uploadRoleService;

    @Inject
    MediaManagementService mediaManagementService;

    @ConfigProperty(name = "media.upload.dir")
    String mediaUploadDir;

    @ConfigProperty(name = "media.upload.chunk-size", defaultValue = "4194304")
    int configuredChunkSize;

    @ConfigProperty(name = "media.upload.session-timeout", defaultValue = "24H")
    Duration sessionTimeout;

    @ConfigProperty(name = "media.upload.max-file-size", defaultValue = "536870912")
    long maxFileSize;

    private Path sessionRoot;

    @PostConstruct
    void initialize() {
        sessionRoot = Path.of(mediaUploadDir).toAbsolutePath().normalize().resolve(".upload-sessions");
        try {
            Files.createDirectories(sessionRoot);
            cleanupExpiredSessions();
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建分片上传目录", exception);
        }
    }

    @Scheduled(every = "1h")
    void removeExpiredSessions() {
        try {
            cleanupExpiredSessions();
        } catch (IOException exception) {
            LOG.warn("定时清理过期分片上传会话失败", exception);
        }
    }

    public Session initiate(User operator, String fileName, String mimeType, long totalSize) {
        validateOperator(operator);
        String normalizedFileName = sanitizeFileName(fileName);
        validateExtension(normalizedFileName);
        String normalizedMime = normalizeMime(mimeType);
        UploadRole role = resolveUploadRole(operator);
        validateUploadRole(role, normalizedMime, totalSize);
        if (totalSize <= 0 || totalSize > maxFileSize) {
            throw new BadRequestException("文件大小超出分片上传限制");
        }

        int chunkSize = normalizeChunkSize();
        int chunkCount = (int) ((totalSize + chunkSize - 1) / chunkSize);
        String uploadId = UUID.randomUUID().toString();
        Path directory = sessionDirectory(uploadId);
        try {
            Files.createDirectories(directory);
            Properties properties = new Properties();
            properties.setProperty("operatorId", operator.id.toString());
            properties.setProperty("fileName", normalizedFileName);
            properties.setProperty("mimeType", normalizedMime);
            properties.setProperty("totalSize", Long.toString(totalSize));
            properties.setProperty("chunkSize", Integer.toString(chunkSize));
            properties.setProperty("chunkCount", Integer.toString(chunkCount));
            properties.setProperty("createdAt", Instant.now().toString());
            writeProperties(directory.resolve(META_FILE), properties);
            return new Session(uploadId, chunkSize, chunkCount, totalSize, List.of(),
                    normalizedFileName, normalizedMime, operator.id);
        } catch (IOException exception) {
            deleteDirectory(directory);
            throw new IllegalStateException("无法创建分片上传会话", exception);
        }
    }

    public Session status(User operator, String uploadId) {
        Session session = loadSession(uploadId);
        verifyOwner(operator, session);
        return withUploadedChunks(session);
    }

    public Session writeChunk(User operator, String uploadId, int chunkIndex,
                              InputStream input, String expectedSha256) {
        Session session = loadSession(uploadId);
        verifyOwner(operator, session);
        if (chunkIndex < 0 || chunkIndex >= session.chunkCount()) {
            throw new BadRequestException("分片序号无效");
        }

        long expectedSize = expectedChunkSize(session, chunkIndex);
        Path directory = sessionDirectory(uploadId);
        Path target = directory.resolve(CHUNK_PREFIX + chunkIndex + ".part");
        Path temporary = directory.resolve(CHUNK_PREFIX + chunkIndex + ".uploading");
        try {
            MessageDigest digest = sha256();
            long copied = copyChunk(input, temporary, expectedSize, digest);
            if (copied != expectedSize) {
                Files.deleteIfExists(temporary);
                throw new BadRequestException("分片大小不正确");
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()
                    && !expectedSha256.equalsIgnoreCase(toHex(digest.digest()))) {
                Files.deleteIfExists(temporary);
                throw new BadRequestException("分片校验失败");
            }
            moveReplacing(temporary, target);
            return withUploadedChunks(session);
        } catch (BadRequestException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("保存上传分片失败", exception);
        }
    }

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public Media complete(User operator, String uploadId) {
        Session session = withUploadedChunks(loadSession(uploadId));
        verifyOwner(operator, session);
        if (session.uploadedChunks().size() != session.chunkCount()) {
            throw new BadRequestException("仍有分片未上传");
        }

        Path directory = sessionDirectory(uploadId);
        Path merged = directory.resolve("merged.upload");
        try {
            mergeChunks(session, merged);
            Media media;
            try (InputStream input = Files.newInputStream(merged)) {
                media = mediaManagementService.storeUploadedMedia(
                        operator, input, session.fileName(), session.mimeType(), session.totalSize());
            }
            deleteDirectory(directory);
            return media;
        } catch (IOException exception) {
            deleteQuietly(merged);
            throw new IllegalStateException("合并上传分片失败", exception);
        }
    }

    private void mergeChunks(Session session, Path merged) throws IOException {
        try (OutputStream output = Files.newOutputStream(
                merged, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (int index = 0; index < session.chunkCount(); index++) {
                Path chunk = sessionDirectory(session.uploadId()).resolve(CHUNK_PREFIX + index + ".part");
                if (!Files.isRegularFile(chunk)) {
                    throw new BadRequestException("缺少第 " + index + " 个分片");
                }
                long expected = expectedChunkSize(session, index);
                if (Files.size(chunk) != expected) {
                    throw new BadRequestException("第 " + index + " 个分片大小不正确");
                }
                Files.copy(chunk, output);
            }
        }
        if (Files.size(merged) != session.totalSize()) {
            throw new BadRequestException("合并后的文件大小不正确");
        }
    }

    private long copyChunk(InputStream input, Path target, long expectedSize,
                           MessageDigest digest) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream source = input;
             OutputStream output = Files.newOutputStream(
                     target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) continue;
                copied += read;
                if (copied > expectedSize) {
                    throw new BadRequestException("分片内容超过声明大小");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return copied;
    }

    private Session withUploadedChunks(Session session) {
        List<Integer> uploaded = new ArrayList<>();
        Path directory = sessionDirectory(session.uploadId());
        for (int index = 0; index < session.chunkCount(); index++) {
            if (Files.isRegularFile(directory.resolve(CHUNK_PREFIX + index + ".part"))) {
                uploaded.add(index);
            }
        }
        return session.withUploadedChunks(uploaded);
    }

    private Session loadSession(String uploadId) {
        Path directory = sessionDirectory(uploadId);
        Path metadata = directory.resolve(META_FILE);
        if (!Files.isRegularFile(metadata)) {
            throw new BadRequestException("分片上传会话不存在或已过期");
        }
        try {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(metadata)) {
                properties.load(input);
            }
            Instant createdAt = Instant.parse(required(properties, "createdAt"));
            if (createdAt.plus(sessionTimeout).isBefore(Instant.now())) {
                deleteDirectory(directory);
                throw new BadRequestException("分片上传会话已过期");
            }
            return new Session(
                    uploadId,
                    Integer.parseInt(required(properties, "chunkSize")),
                    Integer.parseInt(required(properties, "chunkCount")),
                    Long.parseLong(required(properties, "totalSize")),
                    List.of(),
                    required(properties, "fileName"),
                    required(properties, "mimeType"),
                    Long.parseLong(required(properties, "operatorId")));
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取分片上传会话", exception);
        }
    }

    private void verifyOwner(User operator, Session session) {
        validateOperator(operator);
        if (!operator.id.equals(session.operatorId())) {
            throw new BadRequestException("无权操作该分片上传会话");
        }
    }

    private void validateOperator(User operator) {
        if (operator == null || operator.id == null) {
            throw new ForbiddenException("上传操作需要有效的用户身份");
        }
    }

    private UploadRole resolveUploadRole(User operator) {
        String roleName = operator.roleName;
        if (roleName == null || roleName.isBlank()) roleName = RoleConstant.USER;
        UploadRole role = uploadRoleService.findByName(roleName);
        if (role == null) role = uploadRoleService.findByName(RoleConstant.USER);
        return role;
    }

    private void validateUploadRole(UploadRole role, String mimeType, long totalSize) {
        if (role == null || !role.canUpload) {
            throw new ForbiddenException("当前角色不允许上传媒体");
        }
        if (!role.allowedMimeTypes.isEmpty() && !role.allowedMimeTypes.contains(mimeType)) {
            throw new ForbiddenException("当前角色不允许上传该类型文件");
        }
        if (role.maxSingleUploadBytes != null && role.maxSingleUploadBytes > 0
                && totalSize > role.maxSingleUploadBytes) {
            throw new BadRequestException("单文件大小超出限制");
        }
    }

    private Path sessionDirectory(String uploadId) {
        try {
            UUID.fromString(uploadId);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("上传会话标识无效");
        }
        Path directory = sessionRoot.resolve(uploadId).normalize();
        if (!directory.startsWith(sessionRoot)) {
            throw new BadRequestException("上传会话路径无效");
        }
        return directory;
    }

    private int normalizeChunkSize() {
        if (configuredChunkSize < 64 * 1024 || configuredChunkSize > 16 * 1024 * 1024) {
            return 4 * 1024 * 1024;
        }
        return configuredChunkSize;
    }

    private long expectedChunkSize(Session session, int chunkIndex) {
        long offset = (long) chunkIndex * session.chunkSize();
        return Math.min(session.chunkSize(), session.totalSize() - offset);
    }

    private void cleanupExpiredSessions() throws IOException {
        if (!Files.isDirectory(sessionRoot)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionRoot)) {
            for (Path directory : stream) {
                if (!Files.isDirectory(directory)) continue;
                try {
                    Path metadata = directory.resolve(META_FILE);
                    if (Files.getLastModifiedTime(metadata).toInstant().plus(sessionTimeout)
                            .isBefore(Instant.now())) {
                        deleteDirectory(directory);
                    }
                } catch (Exception exception) {
                    LOG.warn("清理过期分片上传会话失败: " + directory, exception);
                }
            }
        }
    }

    private void writeProperties(Path target, Properties properties) throws IOException {
        try (OutputStream output = Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "WindBlog media upload session");
        }
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new BadRequestException("上传会话元数据缺失");
        return value;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("系统不支持 SHA-256", exception);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }

    private void deleteDirectory(Path directory) {
        if (directory == null || !directory.startsWith(sessionRoot)) return;
        try {
            if (!Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(this::deleteQuietly);
            }
        } catch (IOException exception) {
            LOG.warn("清理分片上传目录失败: " + directory, exception);
        }
    }

    private String normalizeMime(String mimeType) {
        return mimeType == null || mimeType.isBlank() ? "" : mimeType.trim().toLowerCase();
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) return "file";
        String candidate = fileName.trim().replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) candidate = candidate.substring(slash + 1);
        return candidate.isBlank() ? "file" : candidate;
    }

    private void validateExtension(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".bat")
                || lower.endsWith(".cmd") || lower.endsWith(".sh")) {
            throw new BadRequestException("禁止上传该类型文件");
        }
    }

    public record Session(
            String uploadId,
            int chunkSize,
            int chunkCount,
            long totalSize,
            List<Integer> uploadedChunks,
            String fileName,
            String mimeType,
            Long operatorId) {

        public Session withUploadedChunks(List<Integer> chunks) {
            return new Session(uploadId, chunkSize, chunkCount, totalSize,
                    List.copyOf(chunks), fileName, mimeType, operatorId);
        }
    }
}
