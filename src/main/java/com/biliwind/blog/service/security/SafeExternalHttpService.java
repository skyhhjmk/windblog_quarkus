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
import java.net.Inet6Address;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class SafeExternalHttpService {

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
        if (maxResponseBytes <= 0 || maxResponseBytes > 256 * 1024 * 1024) {
            throw new IllegalArgumentException("外部响应大小限制无效");
        }
        URI currentUri = validatePublicHttpUri(url);
        int redirectCount = 0;
        while (true) {
            ExternalHttpResponse response = executeGet(currentUri, userAgent, maxResponseBytes);
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
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("只允许 HTTP 或 HTTPS 外部地址");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("外部地址不允许携带用户信息");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("外部地址缺少主机名");
        }
        resolvePublicAddresses(host);
        return uri;
    }

    private ExternalHttpResponse executeGet(URI uri, String userAgent, int maxResponseBytes) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PublicAddressDnsResolver())
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
                byte[] body = readLimitedBody(response, maxResponseBytes);
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
        if (response.getEntity() == null) {
            return new byte[0];
        }
        long declaredLength = response.getEntity().getContentLength();
        try (InputStream input = response.getEntity().getContent()) {
            return readLimitedStream(input, declaredLength, maxResponseBytes);
        }
    }

    static byte[] readLimitedStream(InputStream input, long declaredLength, int maxResponseBytes) throws Exception {
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
            }
            return output.toByteArray();
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
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("外部地址无法解析");
            }
            for (InetAddress address : addresses) {
                if (!isPublicAddress(address)) {
                    throw new IllegalArgumentException("不允许访问非公网地址");
                }
            }
            return addresses;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("外部地址无法解析");
        }
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()) {
            return false;
        }
        if (address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            int firstByte = bytes[0] & 255;
            if ((firstByte & 254) == 252 || firstByte == 255
                    || (firstByte == 32 && (bytes[1] & 255) == 1
                    && (bytes[2] & 255) == 13 && (bytes[3] & 255) == 184)) {
                return false;
            }
            if (isIpv4MappedAddress(bytes)) {
                byte[] mappedIpv4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                return !isSpecialIpv4Address(mappedIpv4);
            }
        }
        if (address instanceof Inet4Address && isSpecialIpv4Address(address.getAddress())) {
            return false;
        }
        return true;
    }

    private boolean isSpecialIpv4Address(byte[] bytes) {
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

    private boolean isIpv4MappedAddress(byte[] bytes) {
        if (bytes == null || bytes.length != 16 || (bytes[10] & 255) != 255
                || (bytes[11] & 255) != 255) {
            return false;
        }
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
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

    private class PublicAddressDnsResolver implements DnsResolver {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            try {
                return resolvePublicAddresses(host);
            } catch (IllegalArgumentException exception) {
                UnknownHostException unknownHostException = new UnknownHostException("拒绝非公网地址");
                unknownHostException.initCause(exception);
                throw unknownHostException;
            }
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    }
}
