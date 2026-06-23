package com.biliwind.blog.service.ai;

import com.biliwind.blog.model.AiProviderConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
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
            .proxy(ProxySelector.getDefault())
            .build();

    // 缓存不同代理设置的 HttpClient 实例，避免重复创建线程和连接池消耗系统资源
    private static final ConcurrentHashMap<String, HttpClient> clientCache = new ConcurrentHashMap<>();

    public static HttpClient getClient(AiProviderConfig config, ObjectMapper objectMapper) {
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
                    .connectTimeout(TIMEOUT);

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
}
