package com.biliwind.blog.filter;

import com.biliwind.blog.common.security.CsrfTokenManager;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;
import java.util.Set;

/**
 * CSRF 防护过滤器
 * 实现了双重 Cookie 提交验证模式（Double Submit Cookie）
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
@ApplicationScoped
public class CsrfFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Inject
    CsrfTokenManager csrfTokenManager;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();

        // 1. 对于非安全方法（POST, PUT, DELETE 等），执行验证
        if (!SAFE_METHODS.contains(method)) {
            String path = requestContext.getUriInfo().getPath();
            // 排除不需要 CSRF 防护的路径（如 Admin API，通常使用 Bearer Token 已经天然防御 CSRF）
            // 但如果 Admin API 也使用 Cookie 认证，则也需要校验
            // 这里我们主要针对前台 API
            // 针对所有 API 接口进行校验
            if (path.startsWith("/api/") || path.startsWith("/user/api/")) {
                Cookie csrfCookie = requestContext.getCookies().get(CSRF_COOKIE_NAME);
                String csrfHeader = requestContext.getHeaderString(CSRF_HEADER_NAME);

                if (csrfCookie == null || !csrfTokenManager.verifyToken(csrfHeader, csrfCookie.getValue())) {
                    requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                            .type(MediaType.APPLICATION_JSON)
                            .entity(Map.of("success", false, "message", "CSRF token mismatch or missing"))
                            .build());
                }
            }
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        // 2. 在响应中设置 CSRF Cookie，确保前端可以获取
        Cookie existingCookie = requestContext.getCookies().get(CSRF_COOKIE_NAME);
        if (existingCookie == null) {
            String newToken = csrfTokenManager.generateToken();
            NewCookie cookie = new NewCookie.Builder(CSRF_COOKIE_NAME)
                    .value(newToken)
                    .path("/")
                    .httpOnly(false) // 前端需要读取此 Cookie 来设置 Header
                    .secure(false)   // 开发环境设为 false
                    .sameSite(NewCookie.SameSite.LAX)
                    .build();
            responseContext.getHeaders().add("Set-Cookie", cookie);
        }
    }
}
