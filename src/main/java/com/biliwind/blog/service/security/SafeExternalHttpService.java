package com.biliwind.blog.service.security;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;

import java.net.Inet6Address;
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

    public ExternalHttpResponse get(String url, String userAgent) {
        URI currentUri = validatePublicHttpUri(url);
        int redirectCount = 0;
        while (true) {
            ExternalHttpResponse response = executeGet(currentUri, userAgent);
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
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("外部地址缺少主机名");
        }
        resolvePublicAddresses(host);
        return uri;
    }

    private ExternalHttpResponse executeGet(URI uri, String userAgent) {
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
                byte[] body = readLimitedBody(response);
                List<HeaderValue> headers = new ArrayList<>();
                Header[] responseHeaders = response.getHeaders();
                for (Header responseHeader : responseHeaders) {
                    headers.add(new HeaderValue(responseHeader.getName(), responseHeader.getValue()));
                }
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

    private byte[] readLimitedBody(CloseableHttpResponse response) throws Exception {
        if (response.getEntity() == null) {
            return new byte[0];
        }
        long declaredLength = response.getEntity().getContentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("外部响应内容超过限制");
        }
        byte[] body = EntityUtils.toByteArray(response.getEntity());
        if (body.length > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("外部响应内容超过限制");
        }
        return body;
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
            if ((firstByte & 254) == 252) {
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