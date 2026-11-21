package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.RoleConstant;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Generates and verifies HMAC-SHA256 signed tokens for one-click admin actions in notification emails.
 */
@ApplicationScoped
public class AdminEmailActionTokenService {

    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    @ConfigProperty(name = "admin.jwt.secret", defaultValue = "windblog-admin-dev-secret-change-me")
    String signingSecret;

    public enum TargetType {
        COMMENT,
        USER_PROFILE_REVIEW,
        POST,
        LINK
    }

    public enum Action {
        APPROVE,
        REJECT
    }

    public record VerifiedAction(TargetType targetType, long targetId, Action action, long adminId, Instant expiresAt) {
    }

    public String issue(TargetType targetType, long targetId, Action action, long adminId) {
        return issue(targetType, targetId, action, adminId, DEFAULT_TTL);
    }

    public String issue(TargetType targetType, long targetId, Action action, long adminId, Duration ttl) {
        Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? DEFAULT_TTL : ttl;
        return issueWithExpiresAt(targetType, targetId, action, adminId, Instant.now().plus(effectiveTtl));
    }

    String issueWithExpiresAt(TargetType targetType, long targetId, Action action, long adminId, Instant expiresAt) {
        if (targetType == null || action == null || expiresAt == null) {
            throw new IllegalArgumentException("targetType, action and expiresAt are required");
        }
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String payload = targetType.name() + "|" + targetId + "|" + action.name() + "|" + adminId + "|" + expiresAt.getEpochSecond() + "|" + nonce;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return encodedPayload + "." + signature;
    }

    public VerifiedAction verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        int dotIndex = token.indexOf('.');
        if (dotIndex <= 0 || dotIndex >= token.length() - 1) {
            return null;
        }
        String encodedPayload = token.substring(0, dotIndex);
        String actualSignature = token.substring(dotIndex + 1);
        String expectedSignature = sign(encodedPayload);
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                actualSignature.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }

        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        String[] parts = payload.split("\\|");
        if (parts.length != 6) {
            return null;
        }
        try {
            TargetType targetType = TargetType.valueOf(parts[0]);
            long targetId = Long.parseLong(parts[1]);
            Action action = Action.valueOf(parts[2]);
            long adminId = Long.parseLong(parts[3]);
            long expiresAtEpochSec = Long.parseLong(parts[4]);
            if (Instant.now().getEpochSecond() > expiresAtEpochSec) {
                return null;
            }
            return new VerifiedAction(targetType, targetId, action, adminId, Instant.ofEpochSecond(expiresAtEpochSec));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public boolean isActiveAdmin(User user) {
        return user != null
                && user.deletedAt == null
                && user.status == 1
                && (RoleConstant.SUPER_ADMIN.equals(user.roleName) || RoleConstant.ADMIN.equals(user.roleName));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign admin email action token", ex);
        }
    }
}
