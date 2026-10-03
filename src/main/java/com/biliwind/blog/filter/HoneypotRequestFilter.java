package com.biliwind.blog.filter;

import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.HoneypotService;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.container.PreMatching;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inspects requests before REST resource matching, including common decoy paths. */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 50)
@ApplicationScoped
public class HoneypotRequestFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoneypotRequestFilter.class);
    private static final int MAX_BODY_SAMPLE_BYTES = 8 * 1024 * 1024;
    private static final List<String> DECOY_PATHS = List.of(
            "/.env", "/.git/config", "/wp-login.php", "/wp-admin", "/xmlrpc.php",
            "/phpmyadmin", "/phpmyadmin/", "/actuator/env",
            "/vendor/phpunit/phpunit/src/util/php/eval-stdin.php");

    @Inject
    HoneypotService honeypotService;

    @Inject
    ClientIpResolver clientIpResolver;

    @Inject
    RoutingContext routingContext;

    @Override
    public void filter(ContainerRequestContext request) {
        Map<String, List<String>> headers = copyHeaders(request.getHeaders());
        BodySample body = readBodySample(request);
        String uri = request.getUriInfo().getRequestUri().toString();
        String method = request.getMethod();
        HoneypotService.Detection detection = honeypotService.detect(method, uri, headers, body.bytes());
        if (detection.matchedRules().isEmpty()) return;

        String userAgent = firstHeader(headers, "user-agent");
        String action = detection.blocked() ? "BLOCK" : "OBSERVE";
        try {
            ClientIpResolver.ClientIpResolution ip = clientIpResolver.resolve(routingContext);
            honeypotService.record(ip.clientIp(), ip.remoteIp(), method, uri, userAgent,
                    detection.matchedRules(), action, headers, body.bytes(), body.truncated());
        } catch (RuntimeException exception) {
            // Collection failures must not break normal site requests. A configured block still applies.
            LOGGER.warn("蜜罐事件写入失败: method={}, rules={}", method, detection.matchedRules(), exception);
        }

        String path = request.getUriInfo().getPath();
        if (isDecoyPath(path)) {
            request.abortWith(Response.status(Response.Status.NOT_FOUND).build());
        } else if (detection.blocked()) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .header("Cache-Control", "no-store")
                    .entity("请求已拒绝")
                    .build());
        }
    }

    private BodySample readBodySample(ContainerRequestContext request) {
        InputStream input = request.getEntityStream();
        if (input == null || !request.hasEntity()) return new BodySample(new byte[0], false);
        try {
            byte[] sample = input.readNBytes(MAX_BODY_SAMPLE_BYTES);
            int extra = input.read();
            boolean truncated = extra >= 0;
            InputStream replay = new SequenceInputStream(
                    new ByteArrayInputStream(sample),
                    new SequenceInputStream(
                            extra < 0 ? InputStream.nullInputStream() : new ByteArrayInputStream(new byte[]{(byte) extra}),
                            input));
            request.setEntityStream(replay);
            return new BodySample(sample, truncated);
        } catch (IOException exception) {
            LOGGER.debug("读取蜜罐检测样本失败", exception);
            request.setEntityStream(input);
            return new BodySample(new byte[0], false);
        }
    }

    private Map<String, List<String>> copyHeaders(MultivaluedMap<String, String> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name,
                values == null ? List.of() : new ArrayList<>(values)));
        return result;
    }

    private String firstHeader(Map<String, List<String>> headers, String soughtName) {
        return headers.entrySet().stream()
                .filter(entry -> soughtName.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst().orElse(null);
    }

    private boolean isDecoyPath(String rawPath) {
        if (rawPath == null) return false;
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        return DECOY_PATHS.stream().anyMatch(decoy -> path.equalsIgnoreCase(decoy));
    }

    private record BodySample(byte[] bytes, boolean truncated) { }
}
