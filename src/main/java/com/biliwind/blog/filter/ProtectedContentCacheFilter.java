package com.biliwind.blog.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.util.List;

/**
 * 保护私有数据、敏感端点及错误响应绝不落入共享 CDN 缓存。
 */
@Provider
@Priority(Priorities.HEADER_DECORATOR)
@ApplicationScoped
public class ProtectedContentCacheFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        String path = requestContext.getUriInfo() != null ? requestContext.getUriInfo().getPath() : null;
        List<Object> setCookies = responseContext.getHeaders().get("Set-Cookie");
        if (shouldApplyNoStore(responseContext.getStatus(), path, setCookies)) {
            applyNoStore(responseContext.getHeaders());
        }
    }

    public static boolean shouldApplyNoStore(int status, String path, List<Object> setCookies) {
        // 1. 任何 4xx 或 5xx 错误响应（防止 CDN 启发式负缓存雪崩）
        if (status >= 400) {
            return true;
        }

        // 2. 所有管理后台 API、受保护用户端点及内部同步端点
        if (path != null) {
            String normalizedPath = path.startsWith("/") ? path : "/" + path;
            if (normalizedPath.startsWith("/api/admin/")
                    || normalizedPath.startsWith("/api/user/")
                    || normalizedPath.startsWith("/user/")
                    || normalizedPath.startsWith("/api/internal/")) {
                return true;
            }
        }

        // 3. 包含敏感鉴权凭证的 Set-Cookie
        if (setCookies != null && !setCookies.isEmpty()) {
            for (Object cookieObj : setCookies) {
                String cookieStr = String.valueOf(cookieObj);
                if (cookieStr.contains("user_token=") || cookieStr.contains("admin_jwt=")) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void applyNoStore(MultivaluedMap<String, Object> headers) {
        if (headers == null) {
            return;
        }
        headers.putSingle("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.putSingle("Pragma", "no-cache");
        headers.putSingle("Expires", "0");
        headers.putSingle("Vary", "Cookie");
    }
}
