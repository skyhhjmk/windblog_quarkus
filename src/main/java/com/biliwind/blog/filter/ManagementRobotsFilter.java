package com.biliwind.blog.filter;

import com.biliwind.blog.service.security.SecurityHttpSettings;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.smallrye.health.SmallRyeHealth;
import io.smallrye.health.SmallRyeHealthReporter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Quarkus management endpoints under {@code /q/} are served by the Vert.x
 * router and never pass through JAX-RS response filters. This filter:
 * <ul>
 *   <li>Adds {@code X-Robots-Tag: noindex} and baseline security headers to {@code /q/*}.</li>
 *   <li>Enforces the database-managed {@code security_http.health_public_mode} policy on
 *       {@code /q/health*} so external callers never receive internal backend topology
 *       (DB/Redis/ES/MQ channels/gRPC service names) unless explicitly allowed.</li>
 * </ul>
 */
@ApplicationScoped
public class ManagementRobotsFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementRobotsFilter.class);
    private static final String MANAGEMENT_PREFIX = "/q/";
    private static final String HEALTH_ROOT = "/q/health";
    private static final String X_ROBOTS_TAG = "X-Robots-Tag";

    @Inject
    SecurityHttpSettings securityHttpSettings;

    @Inject
    SettingsReader settingsReader;

    @Inject
    Instance<SmallRyeHealthReporter> healthReporterInstance;

    void init(@Observes Filters filters) {
        filters.register(this::handle, 900);
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        String path = request == null ? null : request.path();
        if (path == null || !path.startsWith(MANAGEMENT_PREFIX)) {
            context.next();
            return;
        }

        SecurityHttpSettings.Snapshot policy = securityHttpSettings == null
                ? SecurityHttpSettings.defaults()
                : securityHttpSettings.currentLocal();

        HttpServerResponse response = context.response();
        applyManagementHeaders(response, policy);

        if (!isHealthPath(path)) {
            context.next();
            return;
        }

        if (SecurityHttpSettings.HEALTH_MODE_DETAILED.equals(policy.healthPublicMode())
                || isDetailedCallerAllowed(request, policy)) {
            context.next();
            return;
        }

        if (SecurityHttpSettings.HEALTH_MODE_PRIVATE_ONLY.equals(policy.healthPublicMode())) {
            response.setStatusCode(404)
                    .putHeader("Content-Type", "application/json; charset=UTF-8")
                    .end("{\"status\":\"NOT_FOUND\"}");
            return;
        }

        // Default "summary" mode: return only aggregate status {"status":"UP"|"DOWN"} without internal checks.
        SmallRyeHealthReporter reporter = resolveHealthReporter();
        if (reporter == null) {
            response.setStatusCode(200)
                    .putHeader("Content-Type", "application/json; charset=UTF-8")
                    .end("{\"status\":\"UP\"}");
            return;
        }

        resolveHealthUni(reporter, path).subscribe().with(
                health -> {
                    boolean up = health != null && !health.isDown();
                    int statusCode = up ? 200 : 503;
                    String payload = up ? "{\"status\":\"UP\"}" : "{\"status\":\"DOWN\"}";
                    if (!response.ended()) {
                        response.setStatusCode(statusCode)
                                .putHeader("Content-Type", "application/json; charset=UTF-8")
                                .end(payload);
                    }
                },
                failure -> {
                    LOGGER.warn("汇总健康检查执行失败: path={}", path, failure);
                    if (!response.ended()) {
                        response.setStatusCode(503)
                                .putHeader("Content-Type", "application/json; charset=UTF-8")
                                .end("{\"status\":\"DOWN\"}");
                    }
                }
        );
    }

    private void applyManagementHeaders(HttpServerResponse response, SecurityHttpSettings.Snapshot policy) {
        response.headers().set(X_ROBOTS_TAG, "noindex");
        response.headers().set("Cache-Control", "no-store");
        if (policy.xContentTypeOptionsNosniff()) {
            response.headers().set("X-Content-Type-Options", "nosniff");
        }
        if (!SecurityHttpSettings.DISABLED.equals(policy.xFrameOptions())) {
            response.headers().set("X-Frame-Options", policy.xFrameOptions());
        }
        if (!SecurityHttpSettings.DISABLED.equals(policy.referrerPolicy())) {
            response.headers().set("Referrer-Policy", policy.referrerPolicy());
        }
        if (policy.hstsEnabled()) {
            response.headers().set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
    }

    boolean isDetailedCallerAllowed(HttpServerRequest request, SecurityHttpSettings.Snapshot policy) {
        if (request == null) {
            return false;
        }
        List<String> allowedCidrs = policy.healthDetailedCidrs();
        if (allowedCidrs.isEmpty()) {
            return false;
        }
        String remoteIp = request.remoteAddress() == null ? null : request.remoteAddress().hostAddress();
        if (!SecurityHttpSettings.matchesAnyCidr(remoteIp, allowedCidrs)) {
            return false;
        }

        // If the request carries proxy forwarding headers (e.g. from Nginx/CDN/ESA on localhost or private network),
        // every forwarded client hop must also be inside allowedCidrs; otherwise treat as external/public traffic.
        List<String> forwardedIps = extractForwardedAddresses(request);
        if (forwardedIps.isEmpty()) {
            return true;
        }
        for (String forwardedIp : forwardedIps) {
            if (!SecurityHttpSettings.matchesAnyCidr(forwardedIp, allowedCidrs)) {
                return false;
            }
        }
        return true;
    }

    private List<String> extractForwardedAddresses(HttpServerRequest request) {
        List<String> headersToCheck = new ArrayList<>();
        headersToCheck.add("X-Forwarded-For");
        headersToCheck.add("Forwarded");
        headersToCheck.add("X-Real-IP");
        String configuredHeader = configuredClientIpHeader();
        if (configuredHeader != null && !configuredHeader.isBlank()
                && headersToCheck.stream().noneMatch(h -> h.equalsIgnoreCase(configuredHeader))) {
            headersToCheck.add(configuredHeader);
        }

        List<String> addresses = new ArrayList<>();
        for (String headerName : headersToCheck) {
            List<String> values = request.headers().getAll(headerName);
            if (values == null || values.isEmpty()) {
                continue;
            }
            for (String line : values) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                for (String part : line.split(",")) {
                    String trimmed = part.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    String candidate = extractForwardedForToken(trimmed);
                    if (candidate != null && !candidate.isBlank()) {
                        addresses.add(candidate);
                    }
                }
            }
        }
        return addresses;
    }

    private String extractForwardedForToken(String element) {
        if (element.indexOf('=') >= 0) {
            for (String pair : element.split(";")) {
                String[] kv = pair.trim().split("=", 2);
                if (kv.length == 2 && "for".equalsIgnoreCase(kv[0].trim())) {
                    return kv[1].trim();
                }
            }
            return null;
        }
        return element;
    }

    private String configuredClientIpHeader() {
        if (settingsReader == null) {
            return null;
        }
        JsonNode network = settingsReader.localRaw(SettingKey.SECURITY_NETWORK);
        if (network != null && network.isObject()) {
            JsonNode header = network.get("client_ip_header");
            if (header != null && header.isTextual()) {
                return header.asText().trim();
            }
        }
        return null;
    }

    static boolean isHealthPath(String path) {
        return HEALTH_ROOT.equals(path) || path.startsWith(HEALTH_ROOT + "/");
    }

    private SmallRyeHealthReporter resolveHealthReporter() {
        if (healthReporterInstance == null || healthReporterInstance.isUnsatisfied()) {
            return null;
        }
        return healthReporterInstance.get();
    }

    private Uni<SmallRyeHealth> resolveHealthUni(SmallRyeHealthReporter reporter, String path) {
        String normalized = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        if (normalized.equals(HEALTH_ROOT + "/ready")) {
            return reporter.getReadinessAsync();
        }
        if (normalized.equals(HEALTH_ROOT + "/live")) {
            return reporter.getLivenessAsync();
        }
        if (normalized.equals(HEALTH_ROOT + "/started")) {
            return reporter.getStartupAsync();
        }
        if (normalized.equals(HEALTH_ROOT + "/well")) {
            return reporter.getWellnessAsync();
        }
        if (normalized.startsWith(HEALTH_ROOT + "/group/")) {
            String group = normalized.substring((HEALTH_ROOT + "/group/").length());
            return reporter.getHealthGroupAsync(group);
        }
        if (normalized.equals(HEALTH_ROOT + "/group")) {
            return reporter.getHealthGroupsAsync();
        }
        return reporter.getHealthAsync();
    }
}
