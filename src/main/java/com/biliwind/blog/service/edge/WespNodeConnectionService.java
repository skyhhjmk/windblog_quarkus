package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.EdgeConnectionType;
import com.biliwind.blog.model.EdgeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

/**
 * Connects an already installed node without exposing an inbound listener.
 * The target URL is probed first, then the supplied target-admin credentials
 * are used only for the login/bootstrap exchange. Credentials and target JWTs
 * are never persisted or returned to the caller.
 */
@jakarta.enterprise.context.ApplicationScoped
public class WespNodeConnectionService {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Inject ObjectMapper mapper;
    @Inject WespSyncService wespSyncService;
    @Inject WespRuntimeConfig runtimeConfig;
    @Inject NodeRoleService nodeRoleService;

    @ConfigProperty(name = "windblog.wesp.active-poll.enabled", defaultValue = "false")
    boolean activePollEnabled;

    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String sitePublicUrl;
    @ConfigProperty(name = "windblog.wesp.local-target-alias")
    Optional<String> localTargetAlias;
    @ConfigProperty(name = "windblog.wesp.local-target-port")
    Optional<Integer> localTargetPort;
    @ConfigProperty(name = "windblog.wesp.local-peer-alias")
    Optional<String> localPeerAlias;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ProbeResult probe(String rawTargetUrl) {
        String targetUrl = resolveReachableUrl(rawTargetUrl);
        return new ProbeResult(true, targetUrl, "目标节点可访问");
    }

    public ConnectionResult connect(ConnectionRequest request) {
        validateRequest(request);
        String authToken = wespSyncService.bootstrapAuthToken();
        if (authToken.length() < 32) {
            throw new WebApplicationException(
                    "当前主节点未配置有效的 WINDBLOG_WESP_AUTH_TOKEN（至少 32 个字符）",
                    Response.Status.SERVICE_UNAVAILABLE);
        }
        if (EdgeNode.findByNodeId(request.nodeId().trim()) != null) {
            throw new WebApplicationException("nodeId 已存在，请使用新的节点 ID", Response.Status.CONFLICT);
        }

        String targetUrl = resolveReachableUrl(request.targetUrl());

        String targetJwt = login(targetUrl, request.username(), request.password());
        String targetStepUp = stepUp(targetUrl, targetJwt, request.password());
        bootstrap(targetUrl, targetJwt, targetStepUp, request, authToken);

        if (activePollEnabled && nodeRoleService.isPrimaryNode()) {
            runtimeConfig.applyBootstrap(nodeRoleService.getNodeId(), targetUrl, authToken,
                    wespSyncService.bootstrapTenantId(), wespSyncService.bootstrapDatasetId(),
                    UUID.randomUUID().toString(), true, request.nodeId().trim());
            wespSyncService.refreshRuntimeConfig();
        }

        EdgeNode node = persistNode(request, targetUrl);
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("reachable", true);
        checks.put("authenticated", true);
        checks.put("bootstrapped", true);
        checks.put("health_status", 200);
        return new ConnectionResult(node, targetUrl, checks,
                activePollEnabled ? "已建立主节点主动连接配置，等待通道就绪"
                        : "目标节点已登录并写入 WESP 运行配置，等待其主动会话");
    }

    private String resolveReachableUrl(String rawTargetUrl) {
        List<String> candidates = endpointCandidates(rawTargetUrl);
        Exception lastFailure = null;
        for (String candidate : candidates) {
            try {
                HttpRequest healthRequest = HttpRequest.newBuilder(uri(candidate + "/q/health/ready"))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Accept", "application/json")
                        .GET().build();
                HttpResponse<String> response = httpClient.send(healthRequest,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return candidate;
                lastFailure = new IOException("HTTP " + response.statusCode());
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        String suffix = lastFailure == null ? "" : ": " + safeMessage(lastFailure);
        throw new WebApplicationException(
                "无法连接目标节点，请确认地址、端口和节点已启动" + suffix,
                Response.Status.BAD_GATEWAY);
    }

    private String peerUrlForTarget() {
        String configured = normalizeConfiguredUrl(sitePublicUrl, "windblog.site.public-url");
        try {
            URI uri = URI.create(configured);
            if (uri.getHost() != null && isLoopback(uri.getHost())) {
                Optional<String> alias = localPeerAlias.filter(value -> !value.isBlank());
                if (alias.isPresent()) return normalizeConfiguredUrl(alias.get(), "windblog.wesp.local-peer-alias");
            }
        } catch (RuntimeException ignored) {
            // The primary URL was already validated above.
        }
        return configured;
    }

    private String login(String targetUrl, String username, String password) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("account", username.trim());
            body.put("password", password);
            HttpRequest request = HttpRequest.newBuilder(uri(targetUrl + "/api/admin/auth/login"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == Response.Status.UNAUTHORIZED.getStatusCode()) {
                // The credentials are for the remote target, not this API caller.
                // Returning 401 makes AdminApiClient treat this as an expired local
                // session and replay the same idempotency key, masking the real error.
                throw new WebApplicationException("目标节点账号或密码错误", Response.Status.BAD_GATEWAY);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebApplicationException("目标节点登录失败（HTTP " + response.statusCode() + "）",
                        Response.Status.BAD_GATEWAY);
            }
            String token = text(mapper.readTree(response.body()), "token");
            if (token == null || token.length() < 16) {
                throw new WebApplicationException("目标节点登录响应缺少有效令牌", Response.Status.BAD_GATEWAY);
            }
            return token;
        } catch (WebApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WebApplicationException("目标节点登录请求失败: " + safeMessage(exception),
                    Response.Status.BAD_GATEWAY);
        }
    }

    private String stepUp(String targetUrl, String targetJwt, String password) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("password", password);
            HttpRequest request = HttpRequest.newBuilder(uri(targetUrl + "/api/admin/auth/step-up"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + targetJwt)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == Response.Status.UNAUTHORIZED.getStatusCode()) {
                throw new WebApplicationException("目标节点账号无权执行连接引导或密码错误",
                        Response.Status.FORBIDDEN);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebApplicationException("目标节点无法签发连接引导凭证（HTTP "
                        + response.statusCode() + "）", Response.Status.BAD_GATEWAY);
            }
            String token = text(mapper.readTree(response.body()), "token");
            if (token == null || token.length() < 16) {
                throw new WebApplicationException("目标节点连接引导凭证无效", Response.Status.BAD_GATEWAY);
            }
            return token;
        } catch (WebApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WebApplicationException("目标节点连接引导请求失败: " + safeMessage(exception),
                    Response.Status.BAD_GATEWAY);
        }
    }

    private void bootstrap(String targetUrl, String targetJwt, String targetStepUp,
                           ConnectionRequest request, String authToken) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("nodeId", request.nodeId().trim());
            body.put("nodeName", request.nodeName() == null || request.nodeName().isBlank()
                    ? request.nodeId().trim() : request.nodeName().trim());
            body.put("region", (request.region() == null ? BlogRegion.GLOBAL : request.region()).name().toLowerCase());
            body.put("peerUrl", activePollEnabled ? "" : peerUrlForTarget());
            body.put("activePoll", activePollEnabled);
            if (request.externalUrl() == null || request.externalUrl().isBlank()) body.putNull("externalUrl");
            else body.put("externalUrl", request.externalUrl().trim());
            body.put("tenantId", wespSyncService.bootstrapTenantId());
            body.put("datasetId", wespSyncService.bootstrapDatasetId());
            body.put("authToken", authToken);
            body.put("incarnation", UUID.randomUUID().toString());

            HttpRequest httpRequest = HttpRequest.newBuilder(uri(targetUrl + "/api/admin/edge-nodes/connection/bootstrap"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + targetJwt)
                    .header("X-Admin-Step-Up", targetStepUp)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == Response.Status.FORBIDDEN.getStatusCode()) {
                throw new WebApplicationException("目标账号需要超级管理员权限才能完成节点引导",
                        Response.Status.FORBIDDEN);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebApplicationException("目标节点未接受连接引导（HTTP "
                        + response.statusCode() + "）", Response.Status.BAD_GATEWAY);
            }
        } catch (WebApplicationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WebApplicationException("发送连接引导失败: " + safeMessage(exception),
                    Response.Status.BAD_GATEWAY);
        }
    }

    @Transactional
    EdgeNode persistNode(ConnectionRequest request, String targetUrl) {
        EdgeNode node = new EdgeNode();
        node.nodeId = request.nodeId().trim();
        node.name = request.nodeName() == null || request.nodeName().isBlank()
                ? node.nodeId : request.nodeName().trim();
        node.region = request.region() == null ? BlogRegion.GLOBAL : request.region();
        node.connectionType = EdgeConnectionType.WESP;
        node.apiUrl = request.targetUrl().trim().replaceAll("/+$", "");
        node.externalUrl = request.externalUrl() == null || request.externalUrl().isBlank()
                ? null : request.externalUrl().trim();
        node.status = "CONFIGURED";
        node.isEnabled = true;
        node.isTrusted = true;
        node.metrics = new java.util.HashMap<>();
        node.metrics.put("connection", "admin-login-bootstrap");
        node.metrics.put("reachableUrl", targetUrl);
        node.persist();
        return node;
    }

    private void validateRequest(ConnectionRequest request) {
        if (request == null) throw new WebApplicationException("节点连接参数不能为空", Response.Status.BAD_REQUEST);
        required(request.nodeId(), 100, "nodeId");
        required(request.targetUrl(), 512, "targetUrl");
        required(request.username(), 200, "username");
        required(request.password(), 512, "password");
        if (request.externalUrl() != null && request.externalUrl().length() > 512) {
            throw new WebApplicationException("externalUrl 过长", Response.Status.BAD_REQUEST);
        }
        normalizeConfiguredUrl(request.targetUrl(), "targetUrl");
    }

    private List<String> endpointCandidates(String raw) {
        String normalized = normalizeConfiguredUrl(raw, "targetUrl");
        List<String> result = new ArrayList<>();
        result.add(normalized);
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host != null && isLoopback(host)) {
                boolean aliasPortMatches = localTargetPort.isEmpty() || uri.getPort() < 0
                        || localTargetPort.get().equals(uri.getPort());
                localTargetAlias.filter(value -> !value.isBlank() && aliasPortMatches).ifPresent(value -> {
                    try {
                        String alias = normalizeConfiguredUrl(value, "windblog.wesp.local-target-alias");
                        String path = uri.getRawPath();
                        if (path != null && !path.isBlank() && !"/".equals(path)) alias += path;
                        if (!result.contains(alias)) result.add(alias);
                    } catch (WebApplicationException ignored) {
                        // An optional development alias must never make a valid
                        // user-supplied target URL invalid.
                    }
                });
                String alias = "http://host.containers.internal";
                if ("https".equalsIgnoreCase(uri.getScheme())) alias = "https://host.containers.internal";
                if (uri.getPort() > 0) alias += ":" + uri.getPort();
                if (uri.getRawPath() != null && !uri.getRawPath().isBlank() && !"/".equals(uri.getRawPath())) {
                    alias += uri.getRawPath();
                }
                if (!result.contains(alias)) result.add(alias);
            }
        } catch (RuntimeException ignored) {
            // normalizeConfiguredUrl already rejects malformed values.
        }
        return result;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
    }

    private static String normalizeConfiguredUrl(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new WebApplicationException(field + " 不能为空", Response.Status.BAD_REQUEST);
        }
        try {
            URI uri = URI.create(raw.trim());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("scheme/host");
            }
            return uri.toString().replaceAll("/+$", "");
        } catch (RuntimeException exception) {
            throw new WebApplicationException(field + " 必须是有效的 http(s) 地址", Response.Status.BAD_REQUEST);
        }
    }

    private static URI uri(String value) {
        return URI.create(value);
    }

    private static void required(String value, int max, String field) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new WebApplicationException(field + " 无效", Response.Status.BAD_REQUEST);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private static String safeMessage(Exception exception) {
        if (exception == null) return "未知错误";
        if (exception instanceof ConnectException) return "连接被拒绝";
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    @RegisterForReflection
    public record ConnectionRequest(String nodeId, String nodeName, BlogRegion region,
                                    String targetUrl, String externalUrl,
                                    String username, String password) {
    }

    @RegisterForReflection
    public record ConnectionResult(EdgeNode node, String targetUrl,
                                   Map<String, Object> checks, String message) {
    }

    @RegisterForReflection
    public record ProbeResult(boolean reachable, String targetUrl, String message) {
    }
}
