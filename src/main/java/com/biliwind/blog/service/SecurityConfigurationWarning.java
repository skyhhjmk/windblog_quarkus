package com.biliwind.blog.service;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;

@ApplicationScoped
public class SecurityConfigurationWarning {

    private static final Logger LOG = Logger.getLogger(SecurityConfigurationWarning.class);
    private static final String DEFAULT_ADMIN_JWT_SECRET = "windblog-admin-dev-secret-change-me";
    private static final String DEFAULT_USER_JWT_SECRET = "windblog-user-dev-secret-change-me";
    private static final String DEFAULT_ADMIN_INIT_PASSWORD = "admin";

    @ConfigProperty(name = "admin.jwt.secret", defaultValue = DEFAULT_ADMIN_JWT_SECRET)
    String adminJwtSecret;

    @ConfigProperty(name = "user.jwt.secret", defaultValue = DEFAULT_USER_JWT_SECRET)
    String userJwtSecret;

    @ConfigProperty(name = "admin.init.password", defaultValue = DEFAULT_ADMIN_INIT_PASSWORD)
    String adminInitPassword;

    @ConfigProperty(name = "security.warn-default-secrets", defaultValue = "true")
    boolean warnDefaultSecrets;

    @ConfigProperty(name = "security.fail-on-default-secrets-in-prod", defaultValue = "true")
    boolean failOnDefaultSecretsInProd;

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String publicUrl;

    @ConfigProperty(name = "quarkus.http.cors.origins", defaultValue = "")
    String corsOrigins;

    @ConfigProperty(name = "rabbitmq-username", defaultValue = "guest")
    String rabbitmqUsername;

    @ConfigProperty(name = "rabbitmq-password", defaultValue = "guest")
    String rabbitmqPassword;

    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    @ConfigProperty(name = "quarkus.swagger-ui.always-include", defaultValue = "false")
    boolean swaggerEnabled;

    @ConfigProperty(name = "security.headers.csp.enforce", defaultValue = "false")
    boolean cspEnforced;

    @ConfigProperty(name = "security.headers.csp.img-sources", defaultValue = "none")
    String cspImageSources;

    @ConfigProperty(name = "security.headers.csp.connect-sources", defaultValue = "none")
    String cspConnectSources;

    @ConfigProperty(name = "security.headers.csp.trusted-types.enabled", defaultValue = "false")
    boolean trustedTypesEnabled;

    @ConfigProperty(name = "security.headers.hsts.enabled", defaultValue = "false")
    boolean hstsEnabled;

    @ConfigProperty(name = "quarkus.http.cors.access-control-allow-credentials", defaultValue = "false")
    boolean corsCredentialsAllowed;

    @ConfigProperty(name = "elasticsearch.ssl-trust-all", defaultValue = "false")
    boolean elasticsearchSslTrustAll;

    @ConfigProperty(name = "elasticsearch.ssl-verify", defaultValue = "full")
    String elasticsearchSslVerify;

    @ConfigProperty(name = "admin.init.enabled", defaultValue = "false")
    boolean adminInitializationEnabled;

    @ConfigProperty(name = "windblog.node.role", defaultValue = "primary")
    String nodeRole;

    @ConfigProperty(name = "quarkus.profile", defaultValue = "prod")
    String quarkusProfile;

    @ConfigProperty(name = "quarkus.grpc.server.ssl.client-auth", defaultValue = "required")
    String grpcServerClientAuth;

    @ConfigProperty(name = "quarkus.grpc.server.plain-text", defaultValue = "false")
    boolean grpcServerPlaintext;

    @ConfigProperty(name = "quarkus.grpc.server.ssl.certificate")
    Optional<String> grpcServerCertificate;

    @ConfigProperty(name = "quarkus.grpc.server.ssl.key")
    Optional<String> grpcServerKey;

    @ConfigProperty(name = "quarkus.grpc.server.ssl.trust-store")
    Optional<String> grpcServerTrustStore;

    @ConfigProperty(name = "quarkus.grpc.server.ssl.trust-store-password")
    Optional<String> grpcServerTrustStorePassword;

    @ConfigProperty(name = "windblog.grpc.client.allow-plaintext-fallback", defaultValue = "false")
    boolean grpcClientPlaintextFallbackAllowed;

    @ConfigProperty(name = "windblog.media.virus-scan.enabled", defaultValue = "false")
    boolean mediaVirusScanEnabled;

    @ConfigProperty(name = "windblog.media.virus-scan.required", defaultValue = "false")
    boolean mediaVirusScanRequired;

    @ConfigProperty(name = "windblog.edge.max-routed-body-bytes", defaultValue = "10485760")
    long edgeMaxRoutedBodyBytes;

    void onStart(@Observes StartupEvent ignored) {
        if (warnDefaultSecrets) {
            checkDefault("ADMIN_JWT_SECRET", adminJwtSecret, DEFAULT_ADMIN_JWT_SECRET);
            checkDefault("USER_JWT_SECRET", userJwtSecret, DEFAULT_USER_JWT_SECRET);
            checkDefault("ADMIN_INIT_PASSWORD", adminInitPassword, DEFAULT_ADMIN_INIT_PASSWORD);
        }
        if (shouldFailStartup()) {
            validateProductionConfiguration();
        } else {
            validateGrpcSecurityConfiguration();
        }
    }

    void validateProductionConfiguration() {
        validateGrpcSecurityConfiguration();
        if (failOnDefaultSecretsInProd && usesDefaultSecret()) {
            throw new IllegalStateException("生产环境禁止使用默认安全配置");
        }
        if (!cookieSecure) {
            throw new IllegalStateException("生产环境必须启用 cookie.secure=true");
        }
        if (isUnsafePublicUrl(publicUrl)) {
            throw new IllegalStateException("生产环境必须配置 HTTPS 的 WINDBLOG_SITE_PUBLIC_URL");
        }
        if (corsOrigins == null || corsOrigins.isBlank() || containsUnsafeCorsOrigin(corsOrigins)) {
            throw new IllegalStateException("生产环境必须配置明确的 CORS_ORIGINS，不能使用 localhost、通配符或空值");
        }
        if (!isEdgeNode() && ("guest".equalsIgnoreCase(rabbitmqUsername)
                || "guest".equalsIgnoreCase(rabbitmqPassword)
                || isWeakSecret(rabbitmqPassword))) {
            throw new IllegalStateException("生产环境必须使用非 guest 且足够强的 RabbitMQ 凭据");
        }
        if ("windblog-dev-event-hash-secret".equals(eventHashSecret) || eventHashSecret.length() < 32) {
            throw new IllegalStateException("生产环境必须配置至少 32 字符的 SECURITY_EVENT_HASH_SECRET");
        }
        if (isWeakSecret(adminJwtSecret) || isWeakSecret(userJwtSecret)) {
            throw new IllegalStateException("生产环境的 JWT secret 必须至少 32 字符且不能使用弱默认值");
        }
        if (swaggerEnabled) {
            throw new IllegalStateException("生产环境禁止公开 Swagger UI");
        }
        if (!cspEnforced || !hstsEnabled) {
            throw new IllegalStateException("生产环境必须启用强制 CSP 和 HSTS");
        }
        if (!trustedTypesEnabled) {
            throw new IllegalStateException("生产环境必须启用 Trusted Types DOM sink 防护");
        }
        if (containsUnsafeCspSource(cspImageSources) || containsUnsafeCspSource(cspConnectSources)) {
            throw new IllegalStateException("生产环境 CSP source 不能使用通配符、引号或不安全 HTTP 来源");
        }
        if (corsCredentialsAllowed) {
            throw new IllegalStateException("生产环境禁止 CORS credentials 全局开启");
        }
        if (elasticsearchSslTrustAll) {
            throw new IllegalStateException("生产环境禁止 Elasticsearch 信任全部 TLS 证书");
        }
        if (!"full".equalsIgnoreCase(elasticsearchSslVerify)) {
            throw new IllegalStateException("生产环境必须启用 Elasticsearch 完整 TLS 证书校验");
        }
        if ("changeit".equalsIgnoreCase(grpcServerTrustStorePassword.orElse(""))) {
            throw new IllegalStateException("生产环境禁止使用默认 gRPC trust-store 密码");
        }
        if (edgeMaxRoutedBodyBytes < 1 || edgeMaxRoutedBodyBytes > 64L * 1024L * 1024L) {
            throw new IllegalStateException("边缘回源请求体上限必须在 1 到 64 MiB 之间");
        }
        if (!isEdgeNode() && (!mediaVirusScanRequired || !mediaVirusScanEnabled)) {
            throw new IllegalStateException("生产环境必须启用并强制执行媒体病毒扫描，未扫描文件不得进入媒体处理链路");
        }
    }

    void validateGrpcSecurityConfiguration() {
        if (grpcServerPlaintext) {
            throw new IllegalStateException("gRPC 服务端禁止使用明文传输，必须启用 TLS");
        }
        if (!"required".equalsIgnoreCase(grpcServerClientAuth)
                || isBlank(grpcServerCertificate)
                || isBlank(grpcServerKey)
                || isBlank(grpcServerTrustStore)) {
            throw new IllegalStateException("gRPC 服务端必须启用完整 mTLS 配置");
        }
        if (grpcClientPlaintextFallbackAllowed) {
            throw new IllegalStateException("gRPC 客户端禁止回退到明文连接");
        }
    }

    private void checkDefault(String environmentName, String actualValue, String defaultValue) {
        if (defaultValue.equals(actualValue)) {
            if (shouldFailStartup() && failOnDefaultSecretsInProd) {
                throw new IllegalStateException("生产环境禁止使用默认安全配置：" + environmentName);
            }
            LOG.warnf("高风险配置：%s 正在使用默认值，请在生产环境中通过环境变量修改", environmentName);
        }
    }

    private boolean shouldFailStartup() {
        return LaunchMode.current() == LaunchMode.NORMAL
                && "prod".equalsIgnoreCase(quarkusProfile);
    }

    private boolean isUnsafePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        URI uri;
        try {
            uri = new URI(value.trim());
        } catch (URISyntaxException exception) {
            return true;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getHost() == null
                || uri.getHost().isBlank()) {
            return true;
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true;
        }

        String normalizedIp = host;
        if (normalizedIp.startsWith("[") && normalizedIp.endsWith("]")) {
            normalizedIp = normalizedIp.substring(1, normalizedIp.length() - 1);
        }
        if (normalizedIp.contains(":")) {
            return isUnsafeIpv6(normalizedIp);
        }

        String[] octets = normalizedIp.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            int third = Integer.parseInt(octets[2]);
            int fourth = Integer.parseInt(octets[3]);
            return isUnsafeIpv4(first, second, third, fourth);
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean isUnsafeIpv6(String value) {
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException exception) {
            return true;
        }
        if (bytes.length != 16) {
            return true;
        }

        boolean mappedIpv4 = true;
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                mappedIpv4 = false;
                break;
            }
        }
        if (mappedIpv4 && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff) {
            return isUnsafeIpv4(bytes[12] & 0xff, bytes[13] & 0xff, bytes[14] & 0xff, bytes[15] & 0xff);
        }

        boolean unspecified = true;
        for (byte valueByte : bytes) {
            if (valueByte != 0) {
                unspecified = false;
                break;
            }
        }
        return unspecified || (bytes[0] == 0 && bytes[15] == 1)
                || (bytes[0] & 0xff) == 0xff
                || (bytes[0] & 0xfe) == 0xfc
                || ((bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xc0) == 0x80)
                || (bytes[0] & 0xff) == 0x20 && (bytes[1] & 0xff) == 0x01
                && (bytes[2] & 0xff) == 0x0d && (bytes[3] & 0xff) == 0xb8;
    }

    private boolean isUnsafeIpv4(int first, int second, int third, int fourth) {
        if (first < 0 || first > 255 || second < 0 || second > 255
                || third < 0 || third > 255 || fourth < 0 || fourth > 255) {
            return true;
        }
        return first == 0 || first == 10 || first == 100 && second >= 64 && second <= 127
                || first == 127 || first == 169 && second == 254
                || first == 192 && second == 0
                || first == 192 && second == 168
                || first == 172 && second >= 16 && second <= 31
                || first == 198 && (second == 18 || second == 19 || second == 51)
                || first == 203 && second == 0 && third == 113
                || first >= 224;
    }

    private boolean isWeakSecret(String value) {
        if (value == null || value.length() < 32) {
            return true;
        }
        String normalized = value.toLowerCase();
        return normalized.contains("change-me") || normalized.contains("password")
                || normalized.contains("secret");
    }

    private boolean usesDefaultSecret() {
        return DEFAULT_ADMIN_JWT_SECRET.equals(adminJwtSecret)
                || DEFAULT_USER_JWT_SECRET.equals(userJwtSecret)
                || DEFAULT_ADMIN_INIT_PASSWORD.equals(adminInitPassword)
                || "windblog-dev-event-hash-secret".equals(eventHashSecret);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isBlank(Optional<String> value) {
        return value.isEmpty() || value.get().isBlank();
    }

    private boolean containsUnsafeCspSource(String sources) {
        if (sources == null || sources.isBlank() || "none".equalsIgnoreCase(sources.trim())) {
            return false;
        }
        String[] values = sources.split(",");
        for (String value : values) {
            String source = value.trim();
            if (source.contains("*") || source.contains("'") || !source.startsWith("https://")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsUnsafeCorsOrigin(String origins) {
        String[] values = origins.split(",");
        for (String value : values) {
            String origin = value.trim();
            if (origin.isBlank() || "*".equals(origin) || isUnsafePublicUrl(origin)) {
                return true;
            }
            try {
                URI uri = new URI(origin);
                if (uri.getRawPath() != null && !uri.getRawPath().isBlank()
                        && !"/".equals(uri.getRawPath())) {
                    return true;
                }
                if (uri.getQuery() != null || uri.getFragment() != null) {
                    return true;
                }
            } catch (URISyntaxException exception) {
                return true;
            }
        }
        return false;
    }

    private boolean isEdgeNode() {
        return "edge".equalsIgnoreCase(nodeRole);
    }
}
