package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 专用 HTTP 客户端服务类。
 * 为每个 AI 渠道供应商实例动态提供带代理/直连配置的 HttpClient。
 */
public class AiHttpClientHelper {

    private static final Logger log = LoggerFactory.getLogger(AiHttpClientHelper.class);

    // 设置 60 秒连接超时
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

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

    static void validateRequest(HttpRequest request) {
        if (request == null || request.uri() == null) {
            throw new IllegalArgumentException("AI 请求地址不能为空");
        }
        validateEndpoint(request.uri());
    }

    private static void validateEndpoint(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("AI endpoint 只允许 HTTP 或 HTTPS");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("AI endpoint 不允许携带用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("AI endpoint 缺少主机名");
        }
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
        try {
            String normalizedHost = host;
            if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
                normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
            }
            for (InetAddress address : InetAddress.getAllByName(normalizedHost)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return true;
                }
                byte[] bytes = address.getAddress();
                if (bytes.length == 16) {
                    if ((bytes[0] & 0xfe) == 0xfc || (bytes[0] & 255) == 255
                            || ((bytes[0] & 255) == 32 && (bytes[1] & 255) == 1
                            && (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184)) {
                        return true;
                    }
                    if (isIpv4MappedAddress(bytes)) {
                        byte[] mappedIpv4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                        if (isSpecialIpv4Address(mappedIpv4)) {
                            return true;
                        }
                    }
                }
                if (bytes.length == 4 && isSpecialIpv4Address(bytes)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            throw new IllegalArgumentException("AI endpoint 主机无法解析");
        }
    }

    private static boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes == null || bytes.length != 16
                || (bytes[10] & 255) != 255 || (bytes[11] & 255) != 255) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSpecialIpv4Address(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            return true;
        }
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        int third = bytes[2] & 255;
        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return true;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return true;
        }
        if (first == 169 && second == 254) {
            return true;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return true;
        }
        if (first == 192 && (second == 0 || second == 168)) {
            return true;
        }
        if (first == 192 && second == 2) {
            return true;
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return true;
        }
        if (first == 198 && second == 51 && third == 100) {
            return true;
        }
        return first == 203 && second == 0 && third == 113;
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
}
