package com.biliwind.blog.filter;

import com.biliwind.blog.common.security.CsrfTokenManager;
import com.biliwind.blog.service.SecurityMetricsService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @Inject
    SecurityMetricsService securityMetricsService;

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();

        if (requiresCsrf(method, requestContext.getUriInfo().getPath())) {
            Cookie csrfCookie = requestContext.getCookies().get(CSRF_COOKIE_NAME);
            String csrfHeader = requestContext.getHeaderString(CSRF_HEADER_NAME);

            if (csrfCookie == null || !csrfTokenManager.verifyToken(csrfHeader, csrfCookie.getValue())) {
                securityMetricsService.increment("csrf.denied", "token_mismatch");
                requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                        .type(MediaType.APPLICATION_JSON)
                        .entity(Map.of("success", false, "message", "CSRF token mismatch or missing"))
                        .build());
            }
        }
    }

    static boolean requiresCsrf(String method, String path) {
        String normalizedMethod = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        if (SAFE_METHODS.contains(normalizedMethod)) {
            return false;
        }
        String normalizedPath = normalizePath(path);
        return (normalizedPath.startsWith("/api/") && !normalizedPath.startsWith("/api/admin/"))
                || normalizedPath.startsWith("/user/api/");
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
                    .secure(cookieSecure)
                    .sameSite(NewCookie.SameSite.LAX)
                    .build();
            responseContext.getHeaders().add("Set-Cookie", cookie);
            if (isCacheableHtmlResponse(responseContext.getMediaType(),
                    responseContext.getHeaderString(HttpHeaders.CACHE_CONTROL))) {
                responseContext.getHeaders().putSingle(HttpHeaders.CACHE_CONTROL, "no-store");
            }
        }
    }

    static boolean isCacheableHtmlResponse(MediaType mediaType, String cacheControl) {
        if (mediaType == null) {
            return false;
        }
        if (!mediaType.isCompatible(MediaType.TEXT_HTML_TYPE)) {
            return false;
        }
        if (cacheControl == null || cacheControl.isBlank()) {
            return false;
        }
        String normalized = cacheControl.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("public") || normalized.contains("max-age")
                || normalized.contains("s-maxage");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if (path.startsWith("/")) {
            return path;
        }
        return "/" + path;
    }
}
