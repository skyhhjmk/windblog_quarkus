package com.biliwind.blog.common.helper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class MediaPathHelper {

    private MediaPathHelper() {
    }

    public static String normalizePublicPath(String path) {
        if (path == null || path.isBlank()) {
            return "/uploads";
        }

        String normalizedPath = path.trim();
        while (normalizedPath.endsWith("/") && normalizedPath.length() > 1) {
            normalizedPath = normalizedPath.substring(0, normalizedPath.length() - 1);
        }
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        return normalizedPath;
    }

    public static String normalizeUrlToPathKey(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }

        try {
            String decodedUrl = URLDecoder.decode(rawUrl, StandardCharsets.UTF_8);
            String pathOnly = removeDomain(decodedUrl);
            pathOnly = removeQueryString(pathOnly);
            return normalizePathKey(pathOnly);
        } catch (Exception exception) {
            return normalizePathKey(rawUrl);
        }
    }

    public static String normalizeLegacyUploadPath(String path) {
        if (path == null) {
            return "";
        }

        String normalizedPath = path.replace("#", "%23");
        if (normalizedPath.startsWith("uploads/")) {
            return "/" + normalizedPath;
        }
        if (normalizedPath.startsWith("/uploads/")) {
            return normalizedPath;
        }
        if (normalizedPath.startsWith("/")) {
            return normalizedPath;
        }
        return "/uploads/" + normalizedPath;
    }

    public static String joinUrl(String prefix, String path) {
        String safePrefix = "";
        if (prefix != null) {
            safePrefix = prefix;
        }

        String safePath = "";
        if (path != null) {
            safePath = path;
        }

        String separator = "/";
        if (safePrefix.endsWith("/") || safePath.startsWith("/")) {
            separator = "";
        }

        String combined = safePrefix + separator + safePath;
        if (combined.contains("://")) {
            int protocolEndIndex = combined.indexOf("://") + 3;
            String protocol = combined.substring(0, protocolEndIndex);
            String rest = combined.substring(protocolEndIndex);
            return protocol + rest.replaceAll("/+", "/");
        }
        return combined.replaceAll("/+", "/");
    }

    private static String removeDomain(String decodedUrl) {
        if (!decodedUrl.contains("://")) {
            return decodedUrl;
        }

        try {
            return new URI(decodedUrl).getPath();
        } catch (Exception exception) {
            int protocolEndIndex = decodedUrl.indexOf("://") + 3;
            int slashIndex = decodedUrl.indexOf("/", protocolEndIndex);
            if (slashIndex >= 0) {
                return decodedUrl.substring(slashIndex);
            }
            return "";
        }
    }

    private static String removeQueryString(String path) {
        int queryIndex = path.indexOf("?");
        if (queryIndex >= 0) {
            return path.substring(0, queryIndex);
        }
        return path;
    }

    private static String normalizePathKey(String path) {
        if (path == null) {
            return "";
        }

        String normalizedPath = path.toLowerCase().trim();
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return normalizedPath;
    }
}
