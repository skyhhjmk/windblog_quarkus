package com.biliwind.blog.filter;

import com.biliwind.blog.service.SecurityMetricsService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;

/** Applies a bounded public-read budget before public pages reach database or search work. */
@Provider
@Priority(Priorities.AUTHENTICATION - 20)
@ApplicationScoped
public class PublicReadRateLimitFilter implements ContainerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int RETRY_AFTER_SECONDS = 60;

    @Inject
    SecurityRateLimitService securityRateLimitService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @Inject
    SecurityMetricsService securityMetricsService;

    @ConfigProperty(name = "security.public-read.page-limit-per-minute", defaultValue = "240")
    int pageLimitPerMinute;

    @ConfigProperty(name = "security.public-read.search-limit-per-minute", defaultValue = "120")
    int searchLimitPerMinute;

    @ConfigProperty(name = "security.public-read.home-limit-per-minute", defaultValue = "300")
    int homeLimitPerMinute;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!isReadMethod(requestContext.getMethod())) {
            return;
        }

        ReadRoute route = classify(requestContext.getUriInfo().getPath());
        if (route == ReadRoute.NONE) {
            return;
        }

        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        String key = "public-read:" + route.key + ":" + digest(clientIp);
        int limit = limitFor(route);
        if (securityRateLimitService.tryAcquire(key, limit, WINDOW)) {
            return;
        }

        securityMetricsService.increment("public-read.denied", "RATE_LIMIT");
        requestContext.abortWith(Response.status(Response.Status.TOO_MANY_REQUESTS)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Retry-After", RETRY_AFTER_SECONDS)
                .header("Cache-Control", "no-store")
                .entity(Map.of(
                        "success", false,
                        "message", "公开阅读请求过于频繁，请稍后重试",
                        "reason", "PUBLIC_READ_RATE_LIMIT"))
                .build());
    }

    static ReadRoute classify(String rawPath) {
        String path = normalizePath(rawPath);
        if ("/".equals(path)) {
            return ReadRoute.HOME;
        }
        if ("/search".equals(path)) {
            return ReadRoute.SEARCH;
        }
        if (path.startsWith("/post/") || path.matches("/[a-zA-Z]{2,8}/post/.*")) {
            return ReadRoute.POST;
        }
        if (path.startsWith("/category/") || path.startsWith("/tag/")) {
            return ReadRoute.BROWSE;
        }
        return ReadRoute.NONE;
    }

    static String digest(String value) {
        String normalized = value == null || value.isBlank() ? "unknown" : value.trim();
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成公开读取限流 key", exception);
        }
    }

    private int limitFor(ReadRoute route) {
        if (route == ReadRoute.SEARCH) {
            return Math.max(1, searchLimitPerMinute);
        }
        if (route == ReadRoute.HOME) {
            return Math.max(1, homeLimitPerMinute);
        }
        if (route == ReadRoute.POST || route == ReadRoute.BROWSE) {
            return Math.max(1, pageLimitPerMinute);
        }
        return 1;
    }

    private boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "/";
        }
        return rawPath.startsWith("/") ? rawPath : "/" + rawPath;
    }

    enum ReadRoute {
        NONE("none"),
        HOME("home"),
        SEARCH("search"),
        POST("post"),
        BROWSE("browse");

        private final String key;

        ReadRoute(String key) {
            this.key = key;
        }
    }
}
