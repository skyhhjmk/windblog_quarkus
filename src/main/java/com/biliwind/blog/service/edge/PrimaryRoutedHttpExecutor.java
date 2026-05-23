package com.biliwind.blog.service.edge;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class PrimaryRoutedHttpExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(PrimaryRoutedHttpExecutor.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8080")
    int httpPort;

    public RoutedHttpExchange.Response execute(RoutedHttpExchange.Request routedRequest) {
        try {
            URI uri = buildUri(routedRequest);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60));

            addHeaders(requestBuilder, routedRequest.headers());

            byte[] requestBody = routedRequest.body();
            if (requestBody == null) {
                requestBody = new byte[0];
            }

            requestBuilder.method(routedRequest.method(), HttpRequest.BodyPublishers.ofByteArray(requestBody));
            HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            return new RoutedHttpExchange.Response(
                    response.statusCode(),
                    collectHeaders(response),
                    response.body(),
                    ""
            );
        } catch (Exception exception) {
            LOGGER.error("执行从节点回源写请求失败", exception);
            return new RoutedHttpExchange.Response(
                    502,
                    Map.of("Content-Type", "application/json"),
                    ("{\"success\":false,\"message\":\"主节点执行写请求失败\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    exception.getMessage()
            );
        }
    }

    private URI buildUri(RoutedHttpExchange.Request routedRequest) {
        String path = routedRequest.path();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        String query = routedRequest.query();
        String uriText = "http://127.0.0.1:" + httpPort + path;
        if (query != null && !query.isBlank()) {
            uriText = uriText + "?" + query;
        }
        return URI.create(uriText);
    }

    private void addHeaders(HttpRequest.Builder requestBuilder, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
            String headerName = headerEntry.getKey();
            String headerValue = headerEntry.getValue();
            if (!canForwardHeader(headerName, headerValue)) {
                continue;
            }
            requestBuilder.header(headerName, headerValue);
        }

        requestBuilder.header("X-WindBlog-Routed-From-Edge", "true");
    }

    private boolean canForwardHeader(String headerName, String headerValue) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        if (headerValue == null) {
            return false;
        }

        String lowerName = headerName.toLowerCase();
        if ("host".equals(lowerName)) {
            return false;
        }
        if ("content-length".equals(lowerName)) {
            return false;
        }
        if ("connection".equals(lowerName)) {
            return false;
        }
        return true;
    }

    private Map<String, String> collectHeaders(HttpResponse<byte[]> response) {
        Map<String, String> headers = new HashMap<>();
        Map<String, java.util.List<String>> responseHeaders = response.headers().map();

        for (Map.Entry<String, java.util.List<String>> headerEntry : responseHeaders.entrySet()) {
            String headerName = headerEntry.getKey();
            java.util.List<String> values = headerEntry.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            if (!shouldReturnHeader(headerName)) {
                continue;
            }
            headers.put(headerName, values.get(0));
        }
        return headers;
    }

    private boolean shouldReturnHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String lowerName = headerName.toLowerCase();
        if ("content-type".equals(lowerName)) {
            return true;
        }
        if ("set-cookie".equals(lowerName)) {
            return true;
        }
        if ("location".equals(lowerName)) {
            return true;
        }
        return "content-disposition".equals(lowerName);
    }
}
