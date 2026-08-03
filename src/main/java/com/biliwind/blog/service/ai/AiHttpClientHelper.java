package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.biliwind.blog.service.security.ExternalHttpEndpointPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * AI 专用 HTTP 客户端服务类。
 * 为每个 AI 渠道供应商实例动态提供带代理/直连配置的 HttpClient。
 */
public class AiHttpClientHelper {

    private static final Logger log = LoggerFactory.getLogger(AiHttpClientHelper.class);

    // 设置 60 秒连接超时
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_NON_STREAM_RESPONSE_BYTES = 8 * 1024 * 1024;

    // 默认的 HttpClient（直连并且遵循系统 JVM 启动参数代理）
    private static final HttpClient defaultClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault())
            .build();

    // 缓存不同代理设置的 HttpClient 实例，避免重复创建线程和连接池消耗系统资源
    private static final ConcurrentHashMap<String, HttpClient> clientCache = new ConcurrentHashMap<>();

    public static HttpClient getClient(AiProviderConfig config, ObjectMapper objectMapper) {
        validateConfiguredEndpoint(config);
        // 如果配置为空或无扩展配置，默认降级使用无代理的 client
        if (config == null) {
            return defaultClient;
        }
        if (config.config == null) {
            return defaultClient;
        }
        if (config.config.isBlank()) {
            return defaultClient;
        }

        try {
            JsonNode root = objectMapper.readTree(config.config);

            boolean proxyEnabled = false;
            if (root.has("proxy_enabled")) {
                proxyEnabled = root.get("proxy_enabled").asBoolean();
            }

            if (proxyEnabled == false) {
                return defaultClient;
            }

            String proxyType = "HTTP";
            if (root.has("proxy_type")) {
                proxyType = root.get("proxy_type").asText();
            }

            String proxyHost = "";
            if (root.has("proxy_host")) {
                proxyHost = root.get("proxy_host").asText();
            }

            int proxyPort = 0;
            if (root.has("proxy_port")) {
                proxyPort = root.get("proxy_port").asInt();
            }

            // 过滤空的主机或无效端口，降级为直连客户端
            if (proxyHost.isBlank()) {
                return defaultClient;
            }
            if (proxyPort <= 0) {
                return defaultClient;
            }

            String cacheKey = proxyType + ":" + proxyHost + ":" + proxyPort;
            HttpClient cached = clientCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER);

            if ("HTTP".equalsIgnoreCase(proxyType)) {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
            } else if ("SOCKS5".equalsIgnoreCase(proxyType) || "SOCKS".equalsIgnoreCase(proxyType)) {
                // Java 的 HttpClient 对 SOCKS5 代理的支持是通过自定义 ProxySelector 返回 SOCKS 类型的 Proxy 对象来实现的
                java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
                builder.proxy(new ProxySelector() {
                    @Override
                    public java.util.List<java.net.Proxy> select(java.net.URI uri) {
                        return java.util.List.of(proxy);
                    }

                    @Override
                    public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                        log.error("SOCKS 代理连接失败: " + sa, ioe);
                    }
                });
            } else {
                builder.proxy(ProxySelector.getDefault());
            }

            HttpClient newClient = builder.build();
            HttpClient existing = clientCache.putIfAbsent(cacheKey, newClient);
            if (existing != null) {
                return existing;
            }
            return newClient;
        } catch (Exception e) {
            log.error("解析 AI 代理配置异常，降级使用默认直连客户端", e);
            return defaultClient;
        }
    }

    public static <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> bodyHandler) {
        validateRequest(request);
        return client.sendAsync(request, bodyHandler);
    }

    static HttpResponse.BodyHandler<String> boundedStringBodyHandler() {
        return boundedStringBodyHandler(MAX_NON_STREAM_RESPONSE_BYTES);
    }

    static HttpResponse.BodyHandler<String> boundedStringBodyHandler(int maxBytes) {
        if (maxBytes <= 0 || maxBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("AI 响应大小限制无效");
        }
        return responseInfo -> HttpResponse.BodySubscribers.mapping(
                new BoundedByteArraySubscriber(maxBytes),
                bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    static void validateRequest(HttpRequest request) {
        if (request == null || request.uri() == null) {
            throw new IllegalArgumentException("AI 请求地址不能为空");
        }
        validateEndpoint(request.uri());
    }

    private static void validateEndpoint(URI uri) {
        URI validatedUri = ExternalHttpEndpointPolicy.validateHttpUri(uri, "AI endpoint");
        String host = validatedUri.getHost();
        if (isPrivateHost(host) && !isExplicitlyAllowed(host)) {
            throw new IllegalArgumentException("AI endpoint 不允许访问私有网络，请配置显式 allowlist");
        }
    }

    private static void validateConfiguredEndpoint(AiProviderConfig config) {
        if (config == null || config.endpoint == null || config.endpoint.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(config.endpoint.trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI endpoint 格式无效");
        }
        validateEndpoint(uri);
    }

    private static boolean isPrivateHost(String host) {
        for (java.net.InetAddress address : ExternalHttpEndpointPolicy.resolveAddresses(host)) {
            if (!ExternalHttpEndpointPolicy.isPublicAddress(address)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitlyAllowed(String host) {
        String allowlist = System.getenv("AI_PRIVATE_ENDPOINT_ALLOWLIST");
        if (allowlist == null || allowlist.isBlank()) {
            try {
                allowlist = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getOptionalValue("ai.private-endpoint-allowlist", String.class).orElse("");
            } catch (Exception ignored) {
                allowlist = "";
            }
        }
        for (String entry : allowlist.split(",")) {
            if (host.equalsIgnoreCase(entry.trim())) {
                return true;
            }
        }
        return false;
    }

    private static final class BoundedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private int receivedBytes;
        private Flow.Subscription subscription;

        private BoundedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public java.util.concurrent.CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (subscription == null) {
                body.completeExceptionally(new IllegalArgumentException("AI 响应订阅无效"));
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    if (remaining > maxBytes - receivedBytes) {
                        throw new IllegalArgumentException("AI 响应内容超过限制");
                    }
                    byte[] bytes = new byte[remaining];
                    buffer.get(bytes);
                    output.write(bytes, 0, bytes.length);
                    receivedBytes = receivedBytes + remaining;
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
