package com.biliwind.blog.controller.api.admin;

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

@ApplicationScoped
public class AdminTokenVerifier {

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "admin.jwt.secret")
    String jwtSecret;

    @ConfigProperty(name = "admin.jwt.issuer")
    String issuer;

    public VerifiedToken verify(String rawToken) {
        if (rawToken == null) {
            return null;
        }

        String token = rawToken.trim();
        if (token.startsWith("[") && token.endsWith("]") && token.length() > 2) {
            token = token.substring(1, token.length() - 1).trim();
        }
        if (token.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            token = token.substring("Bearer ".length()).trim();
        }
        if (token.isBlank()) {
            return null;
        }

        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput, jwtSecret);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.US_ASCII),
                parts[2].getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }

        Map<String, Object> claims = parseClaims(parts[1]);
        if (claims == null) {
            return null;
        }

        String claimIssuer = toStringValue(claims.get("iss"));
        if (!issuer.equals(claimIssuer)) {
            return null;
        }

        Long exp = toLong(claims.get("exp"));
        if (exp == null || exp <= Instant.now().getEpochSecond()) {
            return null;
        }

        Long uid = toLong(claims.get("uid"));
        if (uid == null) {
            return null;
        }

        boolean isAdmin = Boolean.TRUE.equals(claims.get("is_admin"));
        boolean isSuperAdmin = Boolean.TRUE.equals(claims.get("is_super_admin"));
        String roleName = toStringValue(claims.get("role_name"));
        String username = toStringValue(claims.get("upn"));
        return new VerifiedToken(uid, username, isAdmin, isSuperAdmin, roleName);
    }

    private String sign(String signingInput, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException("JWT signature verify failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseClaims(String payloadPart) {
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(payloadPart);
            return objectMapper.readValue(payloadBytes, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    public record VerifiedToken(Long uid, String username, boolean isAdmin, boolean isSuperAdmin, String roleName) {
    }
}
