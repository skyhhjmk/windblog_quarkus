package com.biliwind.blog.service.edge;

import java.util.Locale;
import java.util.Set;

/**
 * Decides which edge-routed requests the primary replays against its own
 * loopback listener.
 *
 * The replay reaches 127.0.0.1 directly, so it bypasses every proxy-level
 * restriction (for example the management CIDR on /q/). Only the shapes an
 * edge actually produces are accepted: write methods on the application APIs
 * from {@code EdgeWriteRoutingFilter}, and the GET deep-search call from
 * {@code SearchController}. Everything else, including management, sync and
 * gRPC paths and any path the router would normalize differently, is refused.
 */
public final class RoutedRequestPolicy {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String DEEP_SEARCH_PATH = "/search/internal/deep";

    private RoutedRequestPolicy() {
    }

    /** Returns null when allowed, otherwise a short machine-readable reason. */
    public static String rejectionReason(String method, String path, String query) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!isSafePath(path)) {
            return "ROUTED_PATH_INVALID";
        }
        if (query != null && !isSafeQuery(query)) {
            return "ROUTED_QUERY_INVALID";
        }
        if ("GET".equals(normalizedMethod)) {
            return DEEP_SEARCH_PATH.equals(path) ? null : "ROUTED_PATH_NOT_ALLOWED";
        }
        if (!WRITE_METHODS.contains(normalizedMethod)) {
            return "ROUTED_METHOD_NOT_ALLOWED";
        }
        if (path.startsWith("/api/") || path.startsWith("/user/") || "/a2a".equals(path)
                || path.startsWith("/a2a/")) {
            return null;
        }
        return "ROUTED_PATH_NOT_ALLOWED";
    }

    static boolean isSafePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.startsWith("//")) {
            return false;
        }
        for (int index = 0; index < path.length(); index = index + 1) {
            char current = path.charAt(index);
            if (current < 0x21 || current == 0x7f || current == '\\' || current == '?' || current == '#'
                    || current == ';') {
                return false;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%25")
                || lower.contains("%3b") || lower.contains("%3f") || lower.contains("%23")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSafeQuery(String query) {
        for (int index = 0; index < query.length(); index = index + 1) {
            char current = query.charAt(index);
            if (current < 0x20 || current == 0x7f || current == '#') {
                return false;
            }
        }
        return true;
    }
}
