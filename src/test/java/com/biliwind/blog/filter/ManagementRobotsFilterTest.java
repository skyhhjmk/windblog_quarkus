package com.biliwind.blog.filter;

import com.biliwind.blog.service.security.SecurityHttpSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagementRobotsFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void allowsDetailedHealthForDirectLoopbackCaller() {
        ManagementRobotsFilter filter = new ManagementRobotsFilter();
        SecurityHttpSettings.Snapshot policy = SecurityHttpSettings.defaults();
        HttpServerRequest request = mockRequest("127.0.0.1", MultiMap.caseInsensitiveMultiMap());

        assertTrue(filter.isDetailedCallerAllowed(request, policy));
    }

    @Test
    void deniesDetailedHealthForPublicCallerThroughLocalProxy() {
        ManagementRobotsFilter filter = new ManagementRobotsFilter();
        SecurityHttpSettings.Snapshot policy = SecurityHttpSettings.defaults();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Forwarded-For", "203.0.113.42");
        HttpServerRequest request = mockRequest("127.0.0.1", headers);

        assertFalse(filter.isDetailedCallerAllowed(request, policy));
    }

    @Test
    void deniesDetailedHealthForDirectPublicCaller() {
        ManagementRobotsFilter filter = new ManagementRobotsFilter();
        SecurityHttpSettings.Snapshot policy = SecurityHttpSettings.defaults();
        HttpServerRequest request = mockRequest("198.51.100.10", MultiMap.caseInsensitiveMultiMap());

        assertFalse(filter.isDetailedCallerAllowed(request, policy));
    }

    @Test
    void allowsDetailedHealthForInternalForwardedChain() {
        ManagementRobotsFilter filter = new ManagementRobotsFilter();
        ObjectNode node = MAPPER.createObjectNode()
                .put("health_detailed_cidrs", "127.0.0.0/8,10.0.0.0/8");
        SecurityHttpSettings.Snapshot policy = SecurityHttpSettings.fromNode(node);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add("X-Forwarded-For", "10.0.1.5, 127.0.0.1");
        HttpServerRequest request = mockRequest("127.0.0.1", headers);

        assertTrue(filter.isDetailedCallerAllowed(request, policy));
    }

    @Test
    void identifiesAllQuarkusHealthPaths() {
        assertTrue(ManagementRobotsFilter.isHealthPath("/q/health"));
        assertTrue(ManagementRobotsFilter.isHealthPath("/q/health/ready"));
        assertTrue(ManagementRobotsFilter.isHealthPath("/q/health/live"));
        assertTrue(ManagementRobotsFilter.isHealthPath("/q/health/started"));
        assertFalse(ManagementRobotsFilter.isHealthPath("/q/metrics"));
        assertFalse(ManagementRobotsFilter.isHealthPath("/api/health"));
    }

    private static HttpServerRequest mockRequest(String remoteIp, MultiMap headers) {
        SocketAddress address = SocketAddress.inetSocketAddress(12345, remoteIp);
        return (HttpServerRequest) Proxy.newProxyInstance(
                HttpServerRequest.class.getClassLoader(),
                new Class<?>[]{HttpServerRequest.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "remoteAddress" -> address;
                    case "headers" -> headers;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
