package com.biliwind.blog.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * 用户 Token 校验器
 * 负责前台用户 JWT Token 的签名验证和信息提取
 */
@ApplicationScoped
public class UserTokenVerifier {

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "user.jwt.secret")
    String jwtSecret;

    @ConfigProperty(name = "user.jwt.issuer")
    String issuer;

    /**
     * 验证 Token 并解析
     *
     * @param token 原始 Token 字符串
     * @return 验证成功返回 VerifiedToken，失败返回 null
     */
    public VerifiedToken verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return null;
        }

        // 1. 验证签名
        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = calculateHmac(signingInput, jwtSecret);

        // 使用安全比较，防止时间攻击
        byte[] expectedBytes = expectedSignature.getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = parts[2].getBytes(StandardCharsets.US_ASCII);

        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            return null;
        }

        // 2. 解析 Payload
        Map<String, Object> claims = parsePayload(parts[1]);
        if (claims == null) {
            return null;
        }

        // 3. 验证签发者
        String claimIssuer = (String) claims.get("iss");
        if (!issuer.equals(claimIssuer)) {
            return null;
        }

        // 4. 验证过期时间
        Long exp = toLong(claims.get("exp"));
        if (exp == null || exp <= Instant.now().getEpochSecond()) {
            return null;
        }

        // 5. 提取用户信息
        Long uid = toLong(claims.get("uid"));
        if (uid == null) {
            return null;
        }

        String username = (String) claims.get("upn");
        String roleName = (String) claims.get("role_name");

        return new VerifiedToken(uid, username, roleName);
    }

    private String calculateHmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payloadBase64) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(payloadBase64);
            return objectMapper.readValue(bytes, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /**
     * 已验证的 Token 信息
     */
    public record VerifiedToken(Long uid, String username, String roleName) {
    }
}
