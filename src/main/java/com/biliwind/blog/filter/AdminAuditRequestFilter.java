package com.biliwind.blog.filter;

import com.biliwind.blog.context.AdminAuditRequestContext;
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

import java.util.UUID;

@Provider
@Priority(Priorities.AUTHENTICATION - 10)
@ApplicationScoped
public class AdminAuditRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditRequestFilter.class);
    public static final String REQUEST_ID_HEADER_NAME = "X-Request-Id";
    private static final String REQUEST_ID_PROPERTY = "admin.audit.request.id";

    @Inject
    AdminAuditRequestContext adminAuditRequestContext;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

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
