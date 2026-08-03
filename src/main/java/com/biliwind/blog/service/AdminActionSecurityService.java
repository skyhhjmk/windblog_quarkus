package com.biliwind.blog.service;

import com.biliwind.blog.common.security.PasswordHasher;
import com.biliwind.blog.model.AdminIdempotencyKey;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;

@ApplicationScoped
public class AdminActionSecurityService {
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "admin.jwt.secret")
    String signingSecret;

    @Transactional
    public String issueStepUp(Long userId, String password) {
        User user = userId == null ? null : User.find(
                "id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null || !passwordHasher.matches(password, user.password)) {
            return null;
        }
        long expiresAt = Instant.now().plus(Duration.ofMinutes(5)).getEpochSecond();
        String nonce = randomNonce();
        String body = userId + ":" + expiresAt + ":" + nonce;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(body.getBytes(StandardCharsets.UTF_8))
                + "." + sign(body);
    }

    public boolean verifyStepUp(String token, Long userId) {
        if (token == null || token.isBlank() || userId == null) {
            return false;
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 2) {
            return false;
        }
        String body;
        try {
            body = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return false;
        }
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII),
                parts[1].getBytes(StandardCharsets.US_ASCII))) {
            return false;
        }
        String[] bodyParts = body.split(":", 3);
        if (bodyParts.length != 3 || !String.valueOf(userId).equals(bodyParts[0])) {
            return false;
        }
        try {
            return Long.parseLong(bodyParts[1]) > Instant.now().getEpochSecond();
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Transactional
    public boolean reserveIdempotencyKey(Long userId, String requestKey, String resource, String action) {
        if (userId == null || requestKey == null || requestKey.isBlank()) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plusHours(24);
        int inserted = entityManager.createNativeQuery(
                        "insert into admin_idempotency_keys "
                                + "(user_id, request_key, resource, action, created_at, expires_at) "
                                + "values (?1, ?2, ?3, ?4, ?5, ?6) "
                                + "on conflict (user_id, request_key) do nothing")
                .setParameter(1, userId)
                .setParameter(2, requestKey.trim())
                .setParameter(3, resource)
                .setParameter(4, action)
                .setParameter(5, now)
                .setParameter(6, expiresAt)
                .executeUpdate();
        return inserted == 1;
    }

    @Scheduled(every = "1h", identity = "admin-idempotency-retention")
    @Transactional
    void purgeExpiredIdempotencyKeys() {
        AdminIdempotencyKey.delete("expiresAt < ?1", OffsetDateTime.now());
    }

    private String randomNonce() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("管理 step-up 签名失败", exception);
        }
    }
}
