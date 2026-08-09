package com.biliwind.blog.service;

import com.biliwind.blog.model.EmailVerificationToken;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@ApplicationScoped
public class EmailVerificationService {
    @Inject
    EmailDeliveryService emailDeliveryService;
    @Inject
    EmailTemplateRenderer emailTemplateRenderer;

    @Inject
    PublicUrlService publicUrlService;

    @Transactional
    public void sendVerification(User user) {
        OffsetDateTime now = OffsetDateTime.now();
        List<EmailVerificationToken> activeTokens = EmailVerificationToken.list(
                "userId = ?1 and consumedAt is null", user.id);
        for (EmailVerificationToken activeToken : activeTokens) {
            activeToken.consumedAt = now;
        }
        String token = createToken();
        EmailVerificationToken verificationToken = new EmailVerificationToken();
        verificationToken.userId = user.id;
        verificationToken.tokenHash = hashToken(token);
        verificationToken.expiresAt = now.plusHours(24);
        verificationToken.persist();
        String verificationUrl = publicUrlService.buildPath("/user/verify-email?token=" + token);
        String html = emailTemplateRenderer.render("验证邮箱", "您好，" + user.username + "：", "请在 24 小时内完成邮箱验证。", "验证邮箱", verificationUrl);
        emailDeliveryService.queue("REGISTRATION_VERIFICATION", user.email, "请验证您的邮箱", html);
    }

    @Transactional
    public boolean verify(String token) {
        EmailVerificationToken verificationToken = EmailVerificationToken.find("tokenHash = ?1 and consumedAt is null", hashToken(token)).firstResult();
        if (verificationToken == null || verificationToken.expiresAt.isBefore(OffsetDateTime.now())) return false;
        User user = User.findById(verificationToken.userId);
        if (user == null || user.deletedAt != null) return false;
        user.emailVerifiedAt = OffsetDateTime.now();
        verificationToken.consumedAt = OffsetDateTime.now();
        return true;
    }

    private String createToken() {
        byte[] tokenBytes = new byte[32];
        new SecureRandom().nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("无法创建邮箱验证令牌", exception);
        }
    }
}
