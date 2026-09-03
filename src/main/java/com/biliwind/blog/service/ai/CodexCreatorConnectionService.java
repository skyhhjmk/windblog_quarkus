package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiConfigType;
import com.biliwind.blog.model.AiProviderConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves the WindBlog-to-Codex-Creator connection from the editable provider
 * record, while retaining deployment configuration as a safe fallback.
 */
@ApplicationScoped
public class CodexCreatorConnectionService {
    public static final String PROVIDER = "CODEX_CREATOR";
    public static final String DEFAULT_PROVIDER_NAME = "Codex Creator (内部)";
    public static final String DEFAULT_ENDPOINT = "http://codex-creator:8681";

    @ConfigProperty(name = "windblog.ai.codex-creator.endpoint", defaultValue = DEFAULT_ENDPOINT)
    String configuredEndpoint;

    @ConfigProperty(name = "windblog.ai.codex-creator.shared-secret", defaultValue = "")
    Optional<String> configuredSecret;

    /** Returns the effective connection without exposing the shared secret. */
    @Transactional
    public ConnectionSettings current() {
        AiProviderConfig stored = findStored();
        return resolve(stored);
    }

    /**
     * Saves only the connection fields used by WindBlog's internal adapter.
     * A blank secret intentionally keeps the existing secret or deployment
     * fallback, so a masked/empty form field cannot erase it accidentally.
     */
    @Transactional
    public ConnectionSettings save(String endpoint, String sharedSecret, Boolean enabled,
                                   String model, boolean clearSharedSecret) {
        String normalizedEndpoint = normalizeEndpoint(endpoint);
        AiProviderConfig config = findStored();
        if (config == null) {
            config = new AiProviderConfig();
            config.type = AiConfigType.PROVIDER;
            config.name = DEFAULT_PROVIDER_NAME;
            config.provider = PROVIDER;
            config.enabled = enabled == null || enabled;
        }

        config.endpoint = normalizedEndpoint;
        if (enabled != null) {
            config.enabled = enabled;
        }
        if (model != null) {
            config.model = model.trim();
        }
        if (clearSharedSecret) {
            config.apiKey = null;
        } else if (hasUsableSecret(sharedSecret)) {
            config.apiKey = sharedSecret.trim();
        }
        config.updatedAt = OffsetDateTime.now();
        if (config.id == null) {
            config.persist();
        }
        return resolve(config);
    }

    public static String normalizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Codex Creator 地址不能为空");
        }
        String normalized = endpoint.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        final URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Codex Creator 地址格式无效", exception);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Codex Creator 地址必须是带主机名的 HTTP(S) 地址");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Codex Creator 地址不能包含账号、查询参数或片段");
        }
        return normalized;
    }

    private AiProviderConfig findStored() {
        return AiProviderConfig.find(
                "provider = ?1 and type = ?2 order by enabled desc, id asc",
                PROVIDER, AiConfigType.PROVIDER).firstResult();
    }

    private ConnectionSettings resolve(AiProviderConfig stored) {
        String endpoint = firstNonBlank(
                stored == null ? null : stored.endpoint,
                configuredEndpoint,
                DEFAULT_ENDPOINT);
        String secret = firstNonBlank(
                stored == null ? null : stored.apiKey,
                configuredSecret.orElse("")
        );
        return new ConnectionSettings(
                stored == null ? null : stored.id,
                stored == null ? DEFAULT_PROVIDER_NAME : stored.name,
                endpoint,
                secret,
                stored != null,
                stored != null && stored.enabled,
                stored == null ? null : stored.model,
                stored == null ? null : stored.updatedAt
        );
    }

    private static boolean hasUsableSecret(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim();
        return !"***REDACTED***".equals(normalized)
                && !"sk-****".equals(normalized)
                && !"******".equals(normalized);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public record ConnectionSettings(
            Long providerId,
            String providerName,
            String endpoint,
            String sharedSecret,
            boolean databaseConfigured,
            boolean enabled,
            String model,
            OffsetDateTime updatedAt
    ) {
        public boolean sharedSecretConfigured() {
            return sharedSecret != null && !sharedSecret.isBlank();
        }
    }
}
