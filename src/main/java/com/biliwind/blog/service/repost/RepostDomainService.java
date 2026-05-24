package com.biliwind.blog.service.repost;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.Locale;

/**
 * 转载与短链域名规范化工具。
 */
@ApplicationScoped
public class RepostDomainService {

    public String normalizeDomainFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost();
            return normalizeDomain(host);
        } catch (Exception ignored) {
            return null;
        }
    }

    public String normalizeDomain(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }

        String normalizedDomain = host.trim().toLowerCase(Locale.ROOT);
        if (normalizedDomain.startsWith("www.")) {
            normalizedDomain = normalizedDomain.substring(4);
        }
        return normalizedDomain;
    }

    public boolean sameDomain(String leftDomain, String rightDomain) {
        String normalizedLeftDomain = normalizeDomain(leftDomain);
        String normalizedRightDomain = normalizeDomain(rightDomain);
        if (normalizedLeftDomain == null) {
            return false;
        }
        if (normalizedRightDomain == null) {
            return false;
        }
        return normalizedLeftDomain.equals(normalizedRightDomain);
    }

    public String resolveRequestDomain(jakarta.ws.rs.core.HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return null;
        }

        String hostHeader = httpHeaders.getHeaderString("Host");
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }

        int portStartIndex = hostHeader.indexOf(":");
        if (portStartIndex >= 0) {
            hostHeader = hostHeader.substring(0, portStartIndex);
        }

        return normalizeDomain(hostHeader);
    }
}
