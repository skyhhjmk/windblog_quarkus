package com.biliwind.blog.service;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.PasswordResetToken;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int EXPIRY_MINUTES = 30;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    EmailDeliveryService emailDeliveryService;

    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Inject
    PublicUrlService publicUrlService;

    @Transactional
    public boolean requestReset(String email) {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail.isBlank()) {
            return false;
        }

        User user = User.find("email = ?1 and deletedAt is null", normalizedEmail).firstResult();
        if (user == null || user.status != 1) {
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<PasswordResetToken> activeTokens = PasswordResetToken.list(
                "userId = ?1 and consumedAt is null", user.id);
        for (PasswordResetToken activeToken : activeTokens) {
            activeToken.consumedAt = now;
        }

        String token = createToken();
        PasswordResetToken resetToken = new PasswordResetToken();
        resetToken.userId = user.id;
        resetToken.tokenHash = hashToken(token);
        resetToken.expiresAt = now.plusMinutes(EXPIRY_MINUTES);
        resetToken.persist();

        String resetUrl = publicUrlService.buildPath("/user/reset-password?token=" + token);
        String html = emailTemplateRenderer.render(
                "重置密码",
                "您好，" + user.username + "：",
                "请在 30 分钟内使用下面的链接设置新密码。如果这不是您的操作，可以忽略此邮件。",
                "重置密码",
                resetUrl);
        emailDeliveryService.queue("PASSWORD_RESET", user.email, "重置 WindBlog 密码", html);
        return true;
    }

    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        if (token == null || token.isBlank() || newPassword == null || newPassword.isBlank()) {
            return false;
        }

        PasswordResetToken resetToken = findUsableToken(token);
        if (resetToken == null) {
            return false;
        }

        User user = User.find("id = ?1 and deletedAt is null", resetToken.userId).firstResult();
        if (user == null || user.status != 1) {
            return false;
        }

        user.password = passwordHasher.hash(newPassword);
        resetToken.consumedAt = OffsetDateTime.now();
        return true;
    }

    @Transactional
    public boolean isTokenValid(String token) {
        return findUsableToken(token) != null;
    }

    @Scheduled(every = "6h", identity = "password-reset-token-retention")
    @Transactional
    void purgeExpiredTokens() {
        OffsetDateTime now = OffsetDateTime.now();
        PasswordResetToken.delete(
                "expiresAt < ?1 or (consumedAt is not null and consumedAt < ?2)",
                now,
                now.minusDays(2));
    }

    private PasswordResetToken findUsableToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        PasswordResetToken resetToken = PasswordResetToken.find(
                "tokenHash = ?1 and consumedAt is null", hashToken(token)).firstResult();
        if (resetToken == null || resetToken.expiresAt == null
                || resetToken.expiresAt.isBefore(OffsetDateTime.now())) {
            return null;
        }
        return resetToken;
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return "";
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String createToken() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建密码重置令牌", exception);
        }
    }
}
