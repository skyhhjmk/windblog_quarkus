package com.biliwind.blog.service.security;

import com.biliwind.blog.model.AdminRevokedToken;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import io.quarkus.scheduler.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@ApplicationScoped
public class AdminTokenRevocationService {

    @Transactional(Transactional.TxType.SUPPORTS)
    public boolean isRevoked(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return true;
        }
        String tokenHash = hashToken(rawToken);
        return AdminRevokedToken.count("tokenHash = ?1 and expiresAt > ?2", tokenHash, OffsetDateTime.now()) > 0;
    }

    @Transactional
    public void revoke(String rawToken, Long userId, long expiresAtEpochSeconds, String reason) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(expiresAtEpochSeconds), ZoneOffset.UTC);
        if (!expiresAt.isAfter(OffsetDateTime.now())) {
            return;
        }
        String tokenHash = hashToken(rawToken);
        if (AdminRevokedToken.count("tokenHash", tokenHash) > 0) {
            return;
        }
        AdminRevokedToken revokedToken = new AdminRevokedToken();
        revokedToken.tokenHash = tokenHash;
        revokedToken.userId = userId;
        revokedToken.expiresAt = expiresAt;
        revokedToken.revokedAt = OffsetDateTime.now();
        revokedToken.reason = reason;
        revokedToken.persist();
    }

    @Scheduled(every = "1h", identity = "admin-token-revocation-cleanup")
    @Transactional
    void removeExpiredTokens() {
        AdminRevokedToken.delete("expiresAt <= ?1", OffsetDateTime.now());
    }

    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成管理员 token 撤销哈希", exception);
        }
    }
}
