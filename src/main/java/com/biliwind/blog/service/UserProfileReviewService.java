package com.biliwind.blog.service;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserProfileReview;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户资料变更审核服务：管理头像 2 MiB 校验与私有隔离、聚合变更单、AI 自动审核及后台人工审批。
 */
@ApplicationScoped
public class UserProfileReviewService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileReviewService.class);

    public static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;
    private static final int MAX_NICKNAME_LENGTH = 50;
    private static final int MAX_BIO_LENGTH = 500;

    @ConfigProperty(name = "media.upload.dir")
    String uploadDir;

    @Inject
    SettingsReader settings;

    @Inject
    AiManager aiManager;

    @Inject
    UserNotificationService notificationService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @Inject
    ManagedExecutor managedExecutor;

    public record AvatarPayload(byte[] bytes, String mimeType) {
    }

    public UserProfileReview submitChange(Long userId, String rawNickname, String rawBio, byte[] avatarBytes) {
        if (userId == null) {
            throw new BadRequestException("请先登录");
        }
        String nextNickname = normalizeOptionalText(rawNickname, MAX_NICKNAME_LENGTH, "昵称长度不能超过 50 个字符");
        String nextBio = normalizeOptionalText(rawBio, MAX_BIO_LENGTH, "个人简介长度不能超过 500 个字符");

        String detectedMime = null;
        if (avatarBytes != null && avatarBytes.length > 0) {
            if (avatarBytes.length > MAX_AVATAR_BYTES) {
                throw new BadRequestException("头像文件不能超过 2 MiB");
            }
            detectedMime = detectImageMime(avatarBytes);
            if (detectedMime == null) {
                throw new BadRequestException("仅支持 JPG、PNG、WebP 或 GIF 格式的头像图片");
            }
        }

        String savedPendingPath = null;
        if (detectedMime != null) {
            savedPendingPath = writePendingAvatar(avatarBytes, detectedMime);
        }

        boolean autoAudit = isAutoAuditEnabled();
        String finalPendingPath = savedPendingPath;
        String finalMime = detectedMime;
        Long finalSize = avatarBytes != null && avatarBytes.length > 0 ? (long) avatarBytes.length : null;

        try {
            UserProfileReview review = QuarkusTransaction.requiringNew().call(() -> {
                User user = User.find("id = ?1 and deletedAt is null", userId).firstResult();
                if (user == null || user.status != 1) {
                    throw new BadRequestException("用户不存在或已禁用");
                }

                UserProfileReview existingPending = UserProfileReview
                        .find("userId = ?1 and status = 'PENDING' order by id desc", userId)
                        .firstResult();

                boolean nicknameChanged = !Objects.equals(emptyToNull(user.nickname), nextNickname);
                boolean bioChanged = !Objects.equals(emptyToNull(user.bio), nextBio);
                boolean avatarChanged = finalPendingPath != null;

                if (!nicknameChanged && !bioChanged && !avatarChanged) {
                    throw new BadRequestException("未检测到资料变更");
                }

                UserProfileReview target;
                String oldPendingToDelete = null;
                if (existingPending != null) {
                    target = existingPending;
                    if (finalPendingPath != null && target.pendingAvatarPath != null) {
                        oldPendingToDelete = target.pendingAvatarPath;
                    }
                } else {
                    target = new UserProfileReview();
                    target.userId = user.id;
                }

                target.status = "PENDING";
                target.oldNickname = user.nickname;
                target.newNickname = nextNickname;
                target.oldBio = user.bio;
                target.newBio = nextBio;
                target.oldAvatar = user.avatar;
                if (finalPendingPath != null) {
                    target.pendingAvatarPath = finalPendingPath;
                    target.pendingAvatarMime = finalMime;
                    target.pendingAvatarSize = finalSize;
                }
                target.aiStatus = autoAudit ? "PENDING" : "NOT_RUN";
                target.aiIsSafe = null;
                target.aiScore = null;
                target.aiReason = null;
                target.aiRawResponse = null;
                target.reviewNote = null;
                target.reviewedBy = null;
                target.reviewedAt = null;
                target.persist();

                if (oldPendingToDelete != null) {
                    deletePendingAvatarQuietly(oldPendingToDelete);
                }
                return target;
            });

            if (autoAudit && review != null && review.id != null) {
                triggerAsyncAiAudit(review.id);
            }
            return review;
        } catch (RuntimeException ex) {
            if (finalPendingPath != null) {
                deletePendingAvatarQuietly(finalPendingPath);
            }
            throw ex;
        }
    }

    public void triggerAsyncAiAudit(Long reviewId) {
        if (reviewId == null) {
            return;
        }
        managedExecutor.submit(() -> {
            try {
                runAiAudit(reviewId);
            } catch (Exception ex) {
                log.warn("异步执行用户资料 AI 审核失败: reviewId={}", reviewId, ex);
            }
        });
    }

    public UserProfileReview runAiAudit(Long reviewId) {
        if (reviewId == null) {
            throw new BadRequestException("审核记录 ID 不能为空");
        }

        UserProfileReview snapshot = QuarkusTransaction.requiringNew().call(() -> {
            UserProfileReview review = UserProfileReview.findById(reviewId);
            if (review == null) {
                throw new NotFoundException("审核记录不存在");
            }
            return review;
        });

        if (!"PENDING".equals(snapshot.status)) {
            return snapshot;
        }

        boolean textChanged = !Objects.equals(emptyToNull(snapshot.oldNickname), emptyToNull(snapshot.newNickname))
                || !Objects.equals(emptyToNull(snapshot.oldBio), emptyToNull(snapshot.newBio));
        boolean hasPendingAvatar = snapshot.pendingAvatarPath != null && !snapshot.pendingAvatarPath.isBlank();

        boolean textOk = true;
        Integer minScore = null;
        StringBuilder reasons = new StringBuilder();
        StringBuilder rawResponses = new StringBuilder();

        if (textChanged) {
            try {
                String textPayload = "新昵称：" + (snapshot.newNickname == null ? "(空)" : snapshot.newNickname)
                        + "\n新个人简介：" + (snapshot.newBio == null ? "(空)" : snapshot.newBio);
                AiResult textResult = aiManager.moderateProfile(textPayload).toCompletableFuture().join();
                if (textResult == null || textResult.isSafe == null) {
                    return markManualRequired(reviewId, "文本 AI 审核未返回有效判定，已转人工审核", null);
                }
                textOk = Boolean.TRUE.equals(textResult.isSafe);
                minScore = textResult.score;
                if (textResult.reason != null && !textResult.reason.isBlank()) {
                    reasons.append("文本审核：").append(textResult.reason.trim());
                }
                if (textResult.rawResponse != null) {
                    rawResponses.append("[text] ").append(textResult.rawResponse).append('\n');
                }
            } catch (Exception ex) {
                log.warn("用户资料文本 AI 审核失败: reviewId={}", reviewId, ex);
                return markManualRequired(reviewId,
                        "文本 AI 审核暂不可用（" + SensitiveMessageSanitizer.sanitize(ex.getMessage()) + "），已转人工审核",
                        null);
            }
        }

        boolean avatarOk = true;
        if (hasPendingAvatar) {
            AvatarPayload payload = loadPendingAvatarBytes(snapshot.pendingAvatarPath);
            if (payload == null) {
                return markManualRequired(reviewId, "待审头像文件不存在，已转人工审核", rawResponses.toString());
            }
            try {
                String dataUrl = "data:" + payload.mimeType() + ";base64,"
                        + Base64.getEncoder().encodeToString(payload.bytes());
                AiResult avatarResult = aiManager
                        .moderateAvatar("请审核该用户上传的新头像是否安全合规。", List.of(dataUrl))
                        .toCompletableFuture()
                        .join();
                if (avatarResult == null || avatarResult.isSafe == null) {
                    return markManualRequired(reviewId, "头像多模态 AI 审核未返回有效判定，已转人工审核",
                            rawResponses.toString());
                }
                avatarOk = Boolean.TRUE.equals(avatarResult.isSafe);
                if (avatarResult.score != null) {
                    minScore = minScore == null ? avatarResult.score : Math.min(minScore, avatarResult.score);
                }
                if (avatarResult.reason != null && !avatarResult.reason.isBlank()) {
                    if (!reasons.isEmpty()) {
                        reasons.append("；");
                    }
                    reasons.append("头像审核：").append(avatarResult.reason.trim());
                }
                if (avatarResult.rawResponse != null) {
                    rawResponses.append("[avatar] ").append(avatarResult.rawResponse);
                }
            } catch (Exception ex) {
                log.info("用户头像多模态 AI 审核不可用，转人工审核: reviewId={}, reason={}", reviewId, ex.getMessage());
                return markManualRequired(reviewId,
                        "未配置可用多模态审核模型或调用失败（" + SensitiveMessageSanitizer.sanitize(ex.getMessage()) + "），已转人工审核",
                        rawResponses.toString());
            }
        }

        boolean combinedSafe = textOk && avatarOk;
        String combinedReason = reasons.isEmpty()
                ? (combinedSafe ? "AI 审核通过" : "AI 审核未通过")
                : reasons.toString();
        String combinedRaw = rawResponses.toString();
        Integer finalScore = minScore;
        boolean allowAutoDecision = isAllowAutoDecisionEnabled();

        return QuarkusTransaction.requiringNew().call(() -> {
            UserProfileReview review = UserProfileReview.findById(reviewId);
            if (review == null || !"PENDING".equals(review.status)) {
                return review;
            }
            review.aiIsSafe = combinedSafe;
            review.aiScore = finalScore;
            review.aiReason = combinedReason;
            review.aiRawResponse = combinedRaw.isBlank() ? null : combinedRaw;
            review.aiStatus = combinedSafe ? "PASSED" : "FLAGGED";

            if (allowAutoDecision) {
                if (combinedSafe) {
                    applyApprovalInTx(review, null, "AI 自动审核通过");
                } else {
                    applyRejectionInTx(review, null, combinedReason);
                }
            } else {
                review.persist();
            }
            return review;
        });
    }

    public UserProfileReview approve(Long reviewId, Long adminId, String note) {
        return QuarkusTransaction.requiringNew().call(() -> {
            UserProfileReview review = UserProfileReview.findById(reviewId);
            if (review == null) {
                throw new NotFoundException("审核记录不存在");
            }
            if (!"PENDING".equals(review.status)) {
                throw new BadRequestException("仅待审核状态记录可执行通过操作");
            }
            applyApprovalInTx(review, adminId, note);
            return review;
        });
    }

    public UserProfileReview reject(Long reviewId, Long adminId, String reason) {
        String normalizedReason = (reason == null || reason.isBlank()) ? "资料内容不符合社区规范" : reason.trim();
        return QuarkusTransaction.requiringNew().call(() -> {
            UserProfileReview review = UserProfileReview.findById(reviewId);
            if (review == null) {
                throw new NotFoundException("审核记录不存在");
            }
            if (!"PENDING".equals(review.status)) {
                throw new BadRequestException("仅待审核状态记录可执行驳回操作");
            }
            applyRejectionInTx(review, adminId, normalizedReason);
            return review;
        });
    }

    public int batchDecision(List<Long> ids, String action, Long adminId, String reason) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try {
                if ("approve".equalsIgnoreCase(action)) {
                    approve(id, adminId, reason);
                    processed++;
                } else if ("reject".equalsIgnoreCase(action)) {
                    reject(id, adminId, reason);
                    processed++;
                }
            } catch (Exception ex) {
                log.warn("批量处理资料审核跳过: id={}, action={}, reason={}", id, action, ex.getMessage());
            }
        }
        return processed;
    }

    public AvatarPayload loadPendingAvatarForUser(Long userId) {
        if (userId == null) {
            return null;
        }
        UserProfileReview review = UserProfileReview
                .find("userId = ?1 and status = 'PENDING' order by id desc", userId)
                .firstResult();
        if (review == null || review.pendingAvatarPath == null || review.pendingAvatarPath.isBlank()) {
            return null;
        }
        return loadPendingAvatarBytes(review.pendingAvatarPath);
    }

    public AvatarPayload loadPendingAvatarByReviewId(Long reviewId) {
        if (reviewId == null) {
            return null;
        }
        UserProfileReview review = UserProfileReview.findById(reviewId);
        if (review == null || review.pendingAvatarPath == null || review.pendingAvatarPath.isBlank()) {
            return null;
        }
        return loadPendingAvatarBytes(review.pendingAvatarPath);
    }

    public AvatarPayload loadPublicAvatar(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return null;
        }
        Path root = avatarsRoot();
        Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String mime = detectImageMime(bytes);
            return new AvatarPayload(bytes, mime == null ? "application/octet-stream" : mime);
        } catch (IOException e) {
            return null;
        }
    }

    private UserProfileReview markManualRequired(Long reviewId, String reason, String rawResponse) {
        return QuarkusTransaction.requiringNew().call(() -> {
            UserProfileReview review = UserProfileReview.findById(reviewId);
            if (review == null) {
                return null;
            }
            review.aiStatus = "MANUAL_REQUIRED";
            review.aiIsSafe = null;
            review.aiReason = reason;
            if (rawResponse != null && !rawResponse.isBlank()) {
                review.aiRawResponse = rawResponse;
            }
            review.persist();
            return review;
        });
    }

    private void applyApprovalInTx(UserProfileReview review, Long adminId, String note) {
        User user = User.find("id = ?1 and deletedAt is null", review.userId).firstResult();
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }

        user.nickname = review.newNickname;
        user.bio = review.newBio;

        if (review.pendingAvatarPath != null && !review.pendingAvatarPath.isBlank()) {
            String publishedUrl = publishPendingAvatar(user.id, review.pendingAvatarPath, review.pendingAvatarMime);
            if (publishedUrl != null) {
                deleteOldPublicAvatarIfManaged(user.avatar);
                user.avatar = publishedUrl;
            }
            deletePendingAvatarQuietly(review.pendingAvatarPath);
            review.pendingAvatarPath = null;
        }

        user.persist();
        review.status = "APPROVED";
        review.reviewedBy = adminId;
        review.reviewedAt = OffsetDateTime.now();
        review.reviewNote = (note == null || note.isBlank()) ? "审核通过" : note.trim();
        review.persist();

        notificationService.create(
                user,
                "PROFILE_REVIEW_APPROVED",
                "个人资料审核已通过",
                "您提交的个人资料变更已通过审核并正式生效。",
                "/user/center#user-tab-settings"
        );
        dataSyncEvent.fire(new DataSyncEvent("USER", user.id, "UPSERT"));
    }

    private void applyRejectionInTx(UserProfileReview review, Long adminId, String reason) {
        if (review.pendingAvatarPath != null && !review.pendingAvatarPath.isBlank()) {
            deletePendingAvatarQuietly(review.pendingAvatarPath);
            review.pendingAvatarPath = null;
        }
        review.status = "REJECTED";
        review.reviewedBy = adminId;
        review.reviewedAt = OffsetDateTime.now();
        review.reviewNote = reason;
        review.persist();

        User user = User.find("id = ?1 and deletedAt is null", review.userId).firstResult();
        if (user != null) {
            notificationService.create(
                    user,
                    "PROFILE_REVIEW_REJECTED",
                    "个人资料审核未通过",
                    "驳回原因：" + reason,
                    "/user/center#user-tab-settings"
            );
        }
    }

    private boolean isAutoAuditEnabled() {
        try {
            return settings.flag(SettingKey.AI_USER_PROFILE_AUDIT, "autoAudit");
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isAllowAutoDecisionEnabled() {
        try {
            return settings.flag(SettingKey.AI_USER_PROFILE_AUDIT, "allowAutoDecision");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String writePendingAvatar(byte[] bytes, String mime) {
        String ext = extensionForMime(mime);
        String fileName = UUID.randomUUID() + ext;
        Path dir = pendingAvatarsRoot();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(fileName), bytes);
            return fileName;
        } catch (IOException e) {
            throw new IllegalStateException("保存待审头像失败", e);
        }
    }

    private String publishPendingAvatar(Long userId, String pendingFileName, String mime) {
        AvatarPayload payload = loadPendingAvatarBytes(pendingFileName);
        if (payload == null) {
            return null;
        }
        String ext = extensionForMime(mime != null ? mime : payload.mimeType());
        String publicFileName = userId + "-" + UUID.randomUUID() + ext;
        Path dir = avatarsRoot();
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(publicFileName), payload.bytes());
            return "/user/avatars/" + publicFileName;
        } catch (IOException e) {
            throw new IllegalStateException("发布头像文件失败", e);
        }
    }

    private AvatarPayload loadPendingAvatarBytes(String pendingFileName) {
        if (pendingFileName == null || pendingFileName.isBlank()
                || pendingFileName.contains("/") || pendingFileName.contains("\\") || pendingFileName.contains("..")) {
            return null;
        }
        Path root = pendingAvatarsRoot();
        Path target = root.resolve(pendingFileName).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            String mime = detectImageMime(bytes);
            return new AvatarPayload(bytes, mime == null ? "image/png" : mime);
        } catch (IOException e) {
            return null;
        }
    }

    private void deletePendingAvatarQuietly(String pendingFileName) {
        if (pendingFileName == null || pendingFileName.isBlank()
                || pendingFileName.contains("/") || pendingFileName.contains("\\") || pendingFileName.contains("..")) {
            return;
        }
        try {
            Path target = pendingAvatarsRoot().resolve(pendingFileName).normalize();
            if (target.startsWith(pendingAvatarsRoot())) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
        }
    }

    private void deleteOldPublicAvatarIfManaged(String oldAvatarUrl) {
        if (oldAvatarUrl == null || !oldAvatarUrl.startsWith("/user/avatars/")) {
            return;
        }
        String fileName = oldAvatarUrl.substring("/user/avatars/".length());
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            return;
        }
        try {
            Path target = avatarsRoot().resolve(fileName).normalize();
            if (target.startsWith(avatarsRoot())) {
                Files.deleteIfExists(target);
            }
        } catch (IOException ignored) {
        }
    }

    private Path pendingAvatarsRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve("private").resolve("pending-avatars");
    }

    private Path avatarsRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize().resolve("avatars");
    }

    private static String normalizeOptionalText(String value, int maxLength, String errorMsg) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new BadRequestException(errorMsg);
        }
        return trimmed;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    public static String detectImageMime(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return "image/png";
        }
        // GIF87a / GIF89a
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
            return "image/gif";
        }
        // RIFF....WEBP
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String extensionForMime(String mime) {
        if (mime == null) {
            return ".png";
        }
        return switch (mime.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".png";
        };
    }
}
