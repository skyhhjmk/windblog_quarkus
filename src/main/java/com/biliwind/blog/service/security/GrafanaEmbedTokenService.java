package com.biliwind.blog.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Issues a short-lived, Grafana-only JWT after the normal Admin boundary has authenticated a user. */
@ApplicationScoped
public class GrafanaEmbedTokenService {

    /** Matches the secure, HttpOnly edge cookie lifetime for the iframe. */
    private static final long TOKEN_TTL_SECONDS = 15 * 60;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "windblog.observability.grafana.embed-secret", defaultValue = "")
    String secret;

    @ConfigProperty(name = "windblog.observability.grafana.embed-path")
    String embedPath;

    public EmbedUrl issue(long userId, String username) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("Grafana embedding is not configured");
        }
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT", "kid", "windblog");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "windblog-observability");
        claims.put("aud", "grafana");
        claims.put("sub", "windblog-admin-" + userId);
        claims.put("username", "windblog-admin-" + userId);
        claims.put("email", "windblog-admin-" + userId + "@local.invalid");
        claims.put("role", "Viewer");
        claims.put("iat", now);
        claims.put("exp", now + TOKEN_TTL_SECONDS);
        claims.put("jti", userId + ":" + now + ":" + Integer.toUnsignedString(username.hashCode()));

        String encodedHeader = encodeJson(header);
        String encodedClaims = encodeJson(claims);
        String token = encodedHeader + "." + encodedClaims + "." + sign(encodedHeader + "." + encodedClaims);
        String separator = embedPath.contains("?") ? "&" : "?";
        return new EmbedUrl(embedPath + separator + "auth_token=" + token, now + TOKEN_TTL_SECONDS);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    objectMapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode Grafana JWT", exception);
        }
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign Grafana JWT", exception);
        }
    }

    /** Kept package-visible for focused token contract tests. */
    boolean hasConfiguredSecret() {
        return secret != null && secret.length() >= 32;
    }

    public record EmbedUrl(String url, long expiresAtEpochSeconds) {
    }
}
