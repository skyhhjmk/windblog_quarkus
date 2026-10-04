package com.biliwind.blog.filter;

import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.HoneypotService;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
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
import java.util.Optional;

/** Inspects matched REST requests after Quarkus has safely read any request body. */
@ApplicationScoped
public class HoneypotRequestFilter {

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

    @ServerRequestFilter(preMatching = true, priority = Priorities.AUTHENTICATION - 50)
    public Uni<Response> handleUnmatchedDecoyPath(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (!isDecoyPath(path)) return Uni.createFrom().nullItem();

        Map<String, List<String>> headers = copyHeaders(request.getHeaders());
        String uri = request.getUriInfo().getRequestUri().toString();
        String method = request.getMethod();
        HoneypotService.Detection detection = honeypotService.detect(method, uri, headers, new byte[0]);
        if (detection.matchedRules().isEmpty()) {
            return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build());
        }

        ClientIpResolver.ClientIpResolution ip;
        try {
            ip = clientIpResolver.resolve(routingContext);
        } catch (RuntimeException exception) {
            LOGGER.warn("蜜罐诱饵路径客户端地址解析失败: method={}, path={}", method, path, exception);
            return Uni.createFrom().item(Response.status(Response.Status.NOT_FOUND).build());
        }
        String userAgent = firstHeader(headers, "user-agent");
        return Uni.createFrom().item(() -> {
            try {
                honeypotService.record(ip.clientIp(), ip.remoteIp(), method, uri, userAgent,
                        detection.matchedRules(), detection.blocked() ? "BLOCK" : "OBSERVE",
                        headers, new byte[0], false);
            } catch (RuntimeException exception) {
                LOGGER.warn("蜜罐诱饵路径事件写入失败: method={}, rules={}", method, detection.matchedRules(), exception);
            }
            return Response.status(Response.Status.NOT_FOUND).build();
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @ServerRequestFilter(priority = Priorities.AUTHENTICATION - 50, readBody = true)
    public Optional<Response> filter(ContainerRequestContext request) {
        Map<String, List<String>> headers = copyHeaders(request.getHeaders());
        BodySample body = readBodySample(request);
        String uri = request.getUriInfo().getRequestUri().toString();
        String method = request.getMethod();
        HoneypotService.Detection detection = honeypotService.detect(method, uri, headers, body.bytes());
        if (detection.matchedRules().isEmpty()) return Optional.empty();

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
            return Optional.of(Response.status(Response.Status.NOT_FOUND).build());
        } else if (detection.blocked()) {
            return Optional.of(Response.status(Response.Status.FORBIDDEN)
                    .header("Cache-Control", "no-store")
                    .entity("请求已拒绝")
                    .build());
        }
        return Optional.empty();
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
