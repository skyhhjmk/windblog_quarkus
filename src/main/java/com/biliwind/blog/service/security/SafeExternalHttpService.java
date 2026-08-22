package com.biliwind.blog.service.security;

import com.biliwind.blog.service.SecurityMetricsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

@ApplicationScoped
public class SafeExternalHttpService {

    @FunctionalInterface
    public interface DownloadProgressListener {
        void onProgress(long downloadedBytes, long totalBytes, int statusCode, int redirectCount);
    }

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_HEADER_VALUE_LENGTH = 8 * 1024;
    private static final int MAX_TOTAL_HEADER_LENGTH = 64 * 1024;

    @Inject
    SecurityMetricsService securityMetricsService;

    public ExternalHttpResponse get(String url, String userAgent) {
        return get(url, userAgent, MAX_RESPONSE_BYTES);
    }

    public ExternalHttpResponse get(String url, String userAgent, int maxResponseBytes) {
        return get(url, userAgent, maxResponseBytes, null);
    }

    public ExternalHttpResponse get(String url, String userAgent, int maxResponseBytes,
                                    DownloadProgressListener listener) {
        if (maxResponseBytes <= 0 || maxResponseBytes > 256 * 1024 * 1024) {
            throw new IllegalArgumentException("外部响应大小限制无效");
        }
        URI currentUri = validatePublicHttpUri(url);
        int redirectCount = 0;
        while (true) {
            ExternalHttpResponse response = executeGet(currentUri, userAgent, maxResponseBytes,
                    listener, redirectCount);
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            if (redirectCount >= MAX_REDIRECTS) {
                throw new IllegalArgumentException("外部地址重定向次数超过限制");
            }
            String location = response.headerValue("Location");
            if (location == null || location.isBlank()) {
                return response;
            }
            currentUri = validatePublicHttpUri(currentUri.resolve(location).toString());
            redirectCount = redirectCount + 1;
        }
    }

    public URI validatePublicHttpUri(String url) {
        try {
            return validatePublicHttpUriInternal(url);
        } catch (IllegalArgumentException exception) {
            if (securityMetricsService != null) {
                securityMetricsService.increment("ssrf.denied", "public_http_policy");
            }
            throw exception;
        }
    }

    private URI validatePublicHttpUriInternal(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("外部地址不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception exception) {
            throw new IllegalArgumentException("外部地址格式无效");
        }
        ExternalHttpEndpointPolicy.validateHttpUri(uri, "外部地址");
        ExternalHttpEndpointPolicy.requirePublicAddresses(uri.getHost());
        return uri;
    }

    private ExternalHttpResponse executeGet(URI uri, String userAgent, int maxResponseBytes,
                                            DownloadProgressListener listener, int redirectCount) {
        InetAddress[] pinnedAddresses = resolvePublicAddresses(uri.getHost());
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedPublicAddressDnsResolver(uri.getHost(), pinnedAddresses))
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(5))
                .setResponseTimeout(Timeout.ofSeconds(10))
                .setRedirectsEnabled(false)
                .build();
        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .build()) {
            HttpGet request = new HttpGet(uri);
            request.setHeader("User-Agent", safeUserAgent(userAgent));
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                List<HeaderValue> headers = validateResponseHeaders(response.getHeaders());
                long declaredLength = response.getEntity() == null
                        ? -1L : response.getEntity().getContentLength();
                notifyProgress(listener, 0L, declaredLength, response.getCode(), redirectCount);
                byte[] body = readLimitedBody(response, maxResponseBytes, listener,
                        response.getCode(), redirectCount);
                return new ExternalHttpResponse(response.getCode(), body, headers);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("外部地址请求失败");
        } finally {
            connectionManager.close();
        }
    }

    private byte[] readLimitedBody(CloseableHttpResponse response, int maxResponseBytes) throws Exception {
        return readLimitedBody(response, maxResponseBytes, null, response.getCode(), 0);
    }

    private byte[] readLimitedBody(CloseableHttpResponse response, int maxResponseBytes,
                                   DownloadProgressListener listener, int statusCode,
                                   int redirectCount) throws Exception {
        if (response.getEntity() == null) {
            return new byte[0];
        }
        long declaredLength = response.getEntity().getContentLength();
        try (InputStream input = response.getEntity().getContent()) {
            return readLimitedStream(input, declaredLength, maxResponseBytes,
                    downloaded -> notifyProgress(listener, downloaded, declaredLength,
                            statusCode, redirectCount));
        }
    }

    static byte[] readLimitedStream(InputStream input, long declaredLength, int maxResponseBytes) throws Exception {
        return readLimitedStream(input, declaredLength, maxResponseBytes, null);
    }

    static byte[] readLimitedStream(InputStream input, long declaredLength, int maxResponseBytes,
                                    LongConsumer progressConsumer) throws Exception {
        if (declaredLength > maxResponseBytes) {
            throw new IllegalArgumentException("外部响应内容超过限制");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxResponseBytes, 8192))) {
            byte[] buffer = new byte[8192];
            int totalBytes = 0;
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                if (bytesRead == 0) {
                    continue;
                }
                if (bytesRead > maxResponseBytes - totalBytes) {
                    throw new IllegalArgumentException("外部响应内容超过限制");
                }
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                if (progressConsumer != null) progressConsumer.accept(totalBytes);
            }
            return output.toByteArray();
        }
    }

    private void notifyProgress(DownloadProgressListener listener, long downloadedBytes,
                                long totalBytes, int statusCode, int redirectCount) {
        if (listener == null) return;
        try {
            listener.onProgress(downloadedBytes, totalBytes, statusCode, redirectCount);
        } catch (RuntimeException ignored) {
            // Progress reporting must never change the secure download outcome.
        }
    }

    static List<HeaderValue> validateResponseHeaders(Header[] responseHeaders) {
        List<HeaderValue> headers = new ArrayList<>();
        int totalLength = 0;
        if (responseHeaders == null) {
            return headers;
        }
        for (Header responseHeader : responseHeaders) {
            String name = responseHeader.getName();
            String value = responseHeader.getValue();
            if (name == null || value == null || value.length() > MAX_HEADER_VALUE_LENGTH) {
                throw new IllegalArgumentException("外部响应头超过限制");
            }
            totalLength = totalLength + name.length() + value.length();
            if (totalLength > MAX_TOTAL_HEADER_LENGTH) {
                throw new IllegalArgumentException("外部响应头总大小超过限制");
            }
            headers.add(new HeaderValue(name, value));
        }
        return headers;
    }

    private String safeUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "WindBlog-Safe-External-Client/1.0";
        }
        return userAgent;
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private InetAddress[] resolvePublicAddresses(String host) {
        InetAddress[] addresses = ExternalHttpEndpointPolicy.resolveAddresses(host);
        for (InetAddress address : addresses) {
            if (!ExternalHttpEndpointPolicy.isPublicAddress(address)) {
                throw new IllegalArgumentException("不允许访问非公网地址");
            }
        }
        return addresses;
    }

    public record ExternalHttpResponse(int statusCode, byte[] body, List<HeaderValue> headers) {
        public String bodyAsText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String headerValue(String expectedName) {
            for (HeaderValue header : headers) {
                if (header.name().equalsIgnoreCase(expectedName)) {
                    return header.value();
                }
            }
            return null;
        }
    }

    public record HeaderValue(String name, String value) {
    }

    static DnsResolver createPinnedPublicAddressDnsResolver(String host, InetAddress[] addresses) {
        return new PinnedPublicAddressDnsResolver(host, addresses);
    }

    static final class PinnedPublicAddressDnsResolver implements DnsResolver {
        private final String pinnedHost;
        private final InetAddress[] pinnedAddresses;

        private PinnedPublicAddressDnsResolver(String host, InetAddress[] addresses) {
            if (host == null || host.isBlank() || addresses == null || addresses.length == 0) {
                throw new IllegalArgumentException("DNS 固定参数无效");
            }
            this.pinnedHost = host;
            this.pinnedAddresses = addresses.clone();
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!pinnedHost.equalsIgnoreCase(host)) {
                throw new UnknownHostException("拒绝解析未固定的外部主机");
            }
            return pinnedAddresses.clone();
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
