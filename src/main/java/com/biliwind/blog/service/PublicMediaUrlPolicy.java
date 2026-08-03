package com.biliwind.blog.service;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps public article rendering from turning arbitrary remote images into a bandwidth proxy. */
public final class PublicMediaUrlPolicy {

    private PublicMediaUrlPolicy() {
    }

    public static void removeDisallowedImages(Document document) {
        Set<String> allowedHosts = allowedHosts();
        for (Element image : document.select("img[src]")) {
            String source = image.attr("src").trim();
            if (!isAllowed(source, allowedHosts)) {
                image.remove();
            }
        }
    }

    private static boolean isAllowed(String source, Set<String> allowedHosts) {
        if (source.isBlank() || (source.startsWith("/") && !source.startsWith("//"))) {
            return true;
        }
        try {
            URI uri = URI.create(source);
            if (uri.getUserInfo() != null) {
                return false;
            }
            String scheme = uri.getScheme();
            if (scheme == null) {
                // URI 形式 //host/path 是协议相对的外链，不是本地相对路径。
                if (source.startsWith("//")) {
                    return isAllowedHost(uri.getHost(), allowedHosts);
                }
                return true;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            return isAllowedHost(uri.getHost(), allowedHosts);
        } catch (Exception exception) {
            return false;
        }
    }

    private static boolean isAllowedHost(String host, Set<String> allowedHosts) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String normalizedHost = host.toLowerCase();
        for (String allowedHost : allowedHosts) {
            if (normalizedHost.equals(allowedHost)
                    || normalizedHost.endsWith("." + allowedHost)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> allowedHosts() {
        Set<String> hosts = new LinkedHashSet<>();
        String configured = ConfigProvider.getConfig()
                .getOptionalValue("windblog.content.allowed-media-hosts", String.class)
                .orElse("");
        addHosts(hosts, configured);
        addUrlHost(hosts, "blog.url");
        addUrlHost(hosts, "windblog.site.public-url");
        return hosts;
    }

    private static void addUrlHost(Set<String> hosts, String key) {
        String value = ConfigProvider.getConfig().getOptionalValue(key, String.class).orElse("");
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null) {
                hosts.add(uri.getHost().toLowerCase());
            }
        } catch (Exception ignored) {
            // Invalid public URL is rejected by the production startup guard.
        }
    }

    private static void addHosts(Set<String> hosts, String values) {
        for (String value : values.split(",")) {
            String normalized = value.trim().toLowerCase();
            if (!normalized.isBlank()) {
                hosts.add(normalized);
            }
        }
    }
}
