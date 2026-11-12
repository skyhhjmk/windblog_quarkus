package com.biliwind.blog.service.edge;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single routing registry for edge/primary request handling.
 *
 * <p>Each route states, independently, whether an edge handles the request
 * locally and whether the primary accepts it as an edge replay. Both decisions
 * default to deny, so a path that matches no grant never becomes a write path
 * or a replay path by accident.</p>
 */
public final class EdgeRoutingPolicy {

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> READ_METHODS = Set.of("GET");
    private static final String DEEP_SEARCH_PATH = "/search/internal/deep";

    private record Route(Pattern pattern, Set<String> methods, boolean edgeLocal,
                         boolean primaryReplay, boolean wespOnly) {
    }

    private static final List<Route> ROUTES = List.of(
            // Edge-local writes: authentication and transport endpoints that
            // must be served by the node the client reached.
            local("/user/api/login"),
            local("/user/api/logout"),
            local("/api/admin/auth/login"),
            local("/api/admin/auth/step-up"),
            local("/api/admin/edge-nodes/connection/bootstrap"),
            new Route(Pattern.compile("/api/admin/edge-nodes/[^/]+/sync"), WRITE_METHODS, true, false, true),
            local("/api/admin/install"),
            local("/q/.*"),
            local("/sync/v1/.*"),
            local("/grpc/.*"),
            // Primary replay grants: only the shapes an edge produces.
            replay("/api/.*"),
            replay("/user/.*"),
            replay("/a2a"),
            replay("/a2a/.*"),
            new Route(Pattern.compile(Pattern.quote(DEEP_SEARCH_PATH)), READ_METHODS, false, true, false)
    );

    private EdgeRoutingPolicy() {
    }

    private static Route local(String pattern) {
        return new Route(Pattern.compile(pattern), WRITE_METHODS, true, false, false);
    }

    private static Route replay(String pattern) {
        return new Route(Pattern.compile(pattern), WRITE_METHODS, false, true, false);
    }

    /** Returns true when the edge must execute this write locally. */
    public static boolean isLocalWriteOnEdge(String path, boolean wespEnabled) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (Route route : ROUTES) {
            if (route.edgeLocal() && (!route.wespOnly() || wespEnabled)
                    && route.pattern().matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /** Returns null when the primary may replay the request, otherwise a reason code. */
    public static String rejectionReason(String method, String path, String query) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        if (!isSafePath(path)) {
            return "ROUTED_PATH_INVALID";
        }
        if (query != null && !isSafeQuery(query)) {
            return "ROUTED_QUERY_INVALID";
        }
        if (READ_METHODS.contains(normalizedMethod)) {
            return matchesReplay(normalizedMethod, path) ? null : "ROUTED_PATH_NOT_ALLOWED";
        }
        if (!WRITE_METHODS.contains(normalizedMethod)) {
            return "ROUTED_METHOD_NOT_ALLOWED";
        }
        return matchesReplay(normalizedMethod, path) ? null : "ROUTED_PATH_NOT_ALLOWED";
    }

    private static boolean matchesReplay(String method, String path) {
        for (Route route : ROUTES) {
            if (route.primaryReplay() && route.methods().contains(method)
                    && route.pattern().matcher(path).matches()) {
                return true;
            }
        }
        return false;
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

    /**
     * Headers an edge node may replay towards the primary. Client-controlled
     * forwarding and internal marker headers are dropped on both sides: the
     * replay arrives from loopback, so a forged X-Forwarded-For or X-WindBlog-*
     * value would otherwise be judged as if a trusted proxy set it.
     */
    public static boolean isForwardableHeader(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String lowerName = headerName.trim().toLowerCase(Locale.ROOT);
        if ("host".equals(lowerName) || "content-length".equals(lowerName) || "connection".equals(lowerName)
                || "transfer-encoding".equals(lowerName) || "upgrade".equals(lowerName)
                || "expect".equals(lowerName) || "te".equals(lowerName) || "keep-alive".equals(lowerName)
                || "proxy-authorization".equals(lowerName) || "proxy-connection".equals(lowerName)) {
            return false;
        }
        if ("forwarded".equals(lowerName) || lowerName.startsWith("x-forwarded-")
                || "x-real-ip".equals(lowerName) || lowerName.endsWith("-connecting-ip")
                || "true-client-ip".equals(lowerName) || "x-client-ip".equals(lowerName)) {
            return false;
        }
        return !lowerName.startsWith("x-windblog-") && !lowerName.startsWith("x-wesp-");
    }
}
