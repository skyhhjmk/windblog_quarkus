package com.biliwind.blog.controller;

import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.service.IpLocationService;
import com.biliwind.blog.service.IpLocationService.IpLocationResult;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 为前台页面提供访客 IP 归属地与欢迎语的同源查询接口。
 */
@Path("/api/public/visitor-location")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class VisitorIpLocationController {

    private static final Duration RATE_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    IpLocationService ipLocationService;

    @Inject
    SecurityRateLimitService securityRateLimitService;

    @Inject
    LanguageContext languageContext;

    @GET
    public Response currentVisitorLocation(@Context RoutingContext routingContext) {
        String clientIp = clientIpResolver.resolve(routingContext).clientIp();
        if (!securityRateLimitService.tryAcquire(
                "public-visitor-location:" + clientIp, RATE_LIMIT_PER_MINUTE, RATE_LIMIT_WINDOW)) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Cache-Control", "no-store")
                    .entity(Map.of("success", false, "message", "请求过于频繁，请稍后重试"))
                    .build();
        }

        IpLocationResult result = ipLocationService.lookup(clientIp);
        boolean isEnglish = isEnglishLanguage();
        String displayLocation = resolveDisplayLocation(result, isEnglish);
        String welcomeMessage = buildWelcomeMessage(displayLocation, result.ip(), isEnglish);
        VisitorLocationResponse payload = new VisitorLocationResponse(
                true,
                result.ip(),
                result.found(),
                result.scope(),
                result.country(),
                result.region(),
                result.city(),
                result.district(),
                result.isp(),
                result.countryCode(),
                result.fullLocation(),
                displayLocation,
                welcomeMessage);

        return Response.ok(payload)
                .header("Cache-Control", "no-store")
                .build();
    }

    private boolean isEnglishLanguage() {
        String lang = languageContext == null ? null : languageContext.getLang();
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("en");
    }

    private String resolveDisplayLocation(IpLocationResult result, boolean isEnglish) {
        if (result == null) {
            return null;
        }
        if (result.found() && result.displayLocation() != null && !result.displayLocation().isBlank()) {
            return result.displayLocation();
        }
        if ("special".equalsIgnoreCase(result.scope())) {
            return isEnglish ? "Local Network" : "本地/内网";
        }
        return null;
    }

    private String buildWelcomeMessage(String displayLocation, String ip, boolean isEnglish) {
        if (displayLocation == null || displayLocation.isBlank()) {
            return null;
        }
        boolean hasIp = ip != null && !ip.isBlank();
        if (isEnglish) {
            return hasIp
                    ? "Welcome, visitor from " + displayLocation + " (IP: " + ip + ")"
                    : "Welcome, visitor from " + displayLocation;
        }
        return hasIp
                ? "欢迎来自" + displayLocation + "的访客（IP：" + ip + "）"
                : "欢迎来自" + displayLocation + "的访客";
    }

    @RegisterForReflection
    public record VisitorLocationResponse(
            boolean success,
            String ip,
            boolean found,
            String scope,
            String country,
            String region,
            String city,
            String district,
            String isp,
            String countryCode,
            String location,
            String displayLocation,
            String welcomeMessage) {
    }
}
