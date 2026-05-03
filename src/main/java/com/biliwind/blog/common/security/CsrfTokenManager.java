package com.biliwind.blog.common.security;

import jakarta.enterprise.context.ApplicationScoped;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * CSRF 令牌管理器
 */
@ApplicationScoped
public class CsrfTokenManager {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_LENGTH = 32;

    /**
     * 生成一个新的 CSRF 令牌
     */
    public String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 验证令牌是否匹配
     */
    public boolean verifyToken(String tokenFromHeader, String tokenFromCookie) {
        if (tokenFromHeader == null || tokenFromCookie == null || tokenFromHeader.isBlank()) {
            return false;
        }
        // 使用 MessageDigest.isEqual 防止时间攻击
        return java.security.MessageDigest.isEqual(
                tokenFromHeader.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                tokenFromCookie.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        );
    }
}
