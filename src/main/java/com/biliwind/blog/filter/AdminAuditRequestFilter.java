package com.biliwind.blog.filter;

import com.biliwind.blog.context.AdminAuditRequestContext;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.security.ClientIpResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
@ApplicationScoped
public class AdminAuditRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditRequestFilter.class);
    public static final String REQUEST_ID_HEADER_NAME = "X-Request-Id";
    private static final String REQUEST_ID_PROPERTY = "admin.audit.request.id";
    private static final String REQUEST_AUDITED_PROPERTY = "admin.audit.request.persisted";

    @Inject
    AdminAuditRequestContext adminAuditRequestContext;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @Inject
    AuditService auditService;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String requestPath = normalizePath(requestContext.getUriInfo().getPath());
        if (!requestPath.startsWith("/api/admin")) {
            return;
        }

        String requestId = resolveRequestId(
                resolveHeaderValue(requestContext, REQUEST_ID_HEADER_NAME));

        String requestMethod = requestContext.getMethod();
        String clientIp = resolveClientIp(requestContext);
        String userAgent = resolveHeaderValue(requestContext, "User-Agent");

        adminAuditRequestContext.setRequestId(requestId);
        adminAuditRequestContext.setRequestMethod(requestMethod);
        adminAuditRequestContext.setRequestPath(requestPath);
        adminAuditRequestContext.setClientIp(clientIp);
        adminAuditRequestContext.setUserAgent(userAgent);

        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String requestPath = normalizePath(requestContext.getUriInfo().getPath());
        if (!requestPath.startsWith("/api/admin")) {
            return;
        }

        Object requestId = requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId != null && !isBlank(requestId.toString())) {
            responseContext.getHeaders().putSingle(REQUEST_ID_HEADER_NAME, requestId.toString());
        }

        if (Boolean.TRUE.equals(requestContext.getProperty(REQUEST_AUDITED_PROPERTY))) {
            return;
        }
        requestContext.setProperty(REQUEST_AUDITED_PROPERTY, Boolean.TRUE);

        int responseStatus = responseContext.getStatus();
        try {
            // 统一记录管理 API 请求，覆盖正常读取、成功写入、权限拒绝以及异常响应。
            // 具体业务变更仍由各控制器保留更详细的旧值/新值审计。
            auditService.log(
                    "admin_request",
                    requestPath,
                    requestContext.getMethod(),
                    null,
                    null,
                    Map.of(
                            "status", responseStatus,
                            "outcome", outcomeForStatus(responseStatus)));
        } catch (RuntimeException exception) {
            // 审计故障不能反过来把一个原本正常的管理请求变成 500。
            LOGGER.warn("记录管理请求审计失败: path={}, method={}, status={}",
                    requestPath, requestContext.getMethod(), responseStatus);
        }
    }

    private String resolveClientIp(ContainerRequestContext requestContext) {
        if (routingContext == null) {
            return "unknown";
        }
        try {
            return clientIpResolver.resolve(routingContext).clientIp();
        } catch (RuntimeException exception) {
            // 审计元数据解析不能阻断管理 API 本身。尤其是在 Redis/配置暂时不可用、
            // 或代理请求上下文不完整时，安全边界仍由 JWT 和权限过滤器负责。
            LOGGER.warn("解析管理请求客户端 IP 失败，继续使用 unknown: {}", exception.getMessage());
            return "unknown";
        }
    }

    static String resolveRequestId(String candidate) {
        if (candidate != null && candidate.matches("[A-Za-z0-9._-]{1,64}")) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    static String outcomeForStatus(int status) {
        if (status >= 200 && status < 400) {
            return "SUCCESS";
        }
        if (status >= 400 && status < 500) {
            return "CLIENT_ERROR";
        }
        if (status >= 500) {
            return "SERVER_ERROR";
        }
        return "UNKNOWN";
    }

    private String resolveHeaderValue(ContainerRequestContext requestContext, String headerName) {
        String value = requestContext.getHeaderString(headerName);
        return isBlank(value) ? null : value;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
