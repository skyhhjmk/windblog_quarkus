package com.biliwind.blog.controller.api.admin;

import java.util.Locale;

/** Central resource/action mapping for the administrator authorization boundary. */
public final class AdminAuthorizationPolicy {

    private AdminAuthorizationPolicy() {
    }

    public static Decision decide(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(path);
        String resource = resourceFor(normalizedPath);
        String action = actionFor(normalizedMethod, normalizedPath, resource);
        boolean superAdminOnly = isSuperAdminOnly(normalizedMethod, normalizedPath);
        boolean stepUpRequired = isStepUpRequired(normalizedMethod, normalizedPath, action);
        return new Decision(resource, action, superAdminOnly, stepUpRequired, stepUpRequired);
    }

    public static boolean requiresStepUp(String method, String path) {
        return decide(method, path).stepUpRequired();
    }

    public static boolean requiresIdempotencyKey(String method, String path) {
        return decide(method, path).idempotencyRequired();
    }

    private static boolean isSuperAdminOnly(String method, String path) {
        if (path.startsWith("/api/admin/security/content-access-tickets")) {
            return true;
        }
        if (path.endsWith("/deployment-zip")) {
            return true;
        }
        if (path.startsWith("/api/admin/database")) {
            return true;
        }
        if (path.startsWith("/api/admin/import") && !"GET".equals(method)) {
            return true;
        }
        if (path.startsWith("/api/admin/node/certificate") && !"GET".equals(method)) {
            return true;
        }
        if ((path.startsWith("/api/admin/edge-nodes")
                || path.startsWith("/api/admin/storage/edge-nodes")) && !"GET".equals(method)) {
            return true;
        }
        if ((path.startsWith("/api/admin/dead-letters")
                || path.startsWith("/api/admin/storage/dead-letter"))
                && ("POST".equals(method) || "DELETE".equals(method))) {
            return true;
        }
        if (path.startsWith("/api/admin/settings") && !"GET".equals(method)) {
            return true;
        }
        if (path.startsWith("/api/admin/outbox") && !"GET".equals(method)) {
            return true;
        }
        if (path.startsWith("/api/admin/permissions/roles") && !"GET".equals(method)) {
            return true;
        }
        return false;
    }

    private static boolean isStepUpRequired(String method, String path, String action) {
        if ("OPTIONS".equals(method)) {
            return false;
        }
        if (isSuperAdminOnly(method, path)) {
            return true;
        }
        if (isHighRiskOperationalAction(method, path)) {
            return true;
        }
        if ("media.download_original".equals(action)) {
            return true;
        }
        if ("GET".equals(method)) {
            return false;
        }
        return "post.publish".equals(action) || "queue.publish".equals(action)
                || "settings.confirm".equals(action) || "settings.rollback".equals(action);
    }

    private static boolean isHighRiskOperationalAction(String method, String path) {
        if (path.startsWith("/api/admin/users/") && (path.endsWith("/wallet/adjust")
                || path.endsWith("/wallet/check-in-reward"))) {
            return "POST".equals(method);
        }
        if (path.startsWith("/api/admin/email-deliveries/") && path.endsWith("/retry")) {
            return "POST".equals(method);
        }
        if (path.equals("/api/admin/email-deliveries/fail-pending")) {
            return "POST".equals(method);
        }
        if (path.startsWith("/api/admin/email-channels/") && path.endsWith("/test")) {
            return "POST".equals(method);
        }
        if (path.startsWith("/api/admin/email-templates/") && path.endsWith("/test")) {
            return "POST".equals(method);
        }
        if ("POST".equals(method) && path.equals("/api/admin/email-campaigns")) {
            return true;
        }
        if (path.startsWith("/api/admin/repost/") && path.endsWith("/revoke")) {
            return "POST".equals(method);
        }
        return path.equals("/api/admin/system/decrypt-error")
                || path.equals("/api/admin/system/sync-cluster-keys");
    }

    private static String resourceFor(String path) {
        if (path.startsWith("/api/admin/users/") && path.contains("/wallet")) {
            return "wallet";
        }
        if (path.startsWith("/api/admin/security/media-download-events")) {
            return "media";
        }
        if (path.startsWith("/api/admin/media")) {
            return "media";
        }
        if (path.startsWith("/api/admin/posts")) {
            return "post";
        }
        if (path.startsWith("/api/admin/settings")) {
            return "settings";
        }
        if (path.startsWith("/api/admin/edge-nodes")
                || path.startsWith("/api/admin/storage/edge-nodes")
                || path.startsWith("/api/admin/node/certificate")) {
            return "edge";
        }
        if (path.startsWith("/api/admin/storage/dead-letter")
                || path.startsWith("/api/admin/dead-letters")) {
            return "dead_letter";
        }
        if (path.startsWith("/api/admin/queues")) {
            return "queue";
        }
        if (path.startsWith("/api/admin/ai")) {
            return "ai";
        }
        if (path.startsWith("/api/admin/import")) {
            return "database";
        }
        if (path.startsWith("/api/admin/database")) {
            return "database";
        }
        if (path.startsWith("/api/admin/permissions")) {
            return "permissions";
        }
        if (path.startsWith("/api/admin/storage/image-processing")) {
            return "image_processing";
        }
        if (path.startsWith("/api/admin/storage")) {
            return "storage";
        }
        String remainder = path.substring("/api/admin".length());
        int slash = remainder.indexOf('/', 1);
        String firstSegment = slash > 0 ? remainder.substring(1, slash) : remainder.substring(1);
        return firstSegment.isBlank() ? "admin" : normalizeResourceSegment(firstSegment);
    }

    private static String normalizeResourceSegment(String segment) {
        return switch (segment) {
            case "users" -> "user";
            case "posts" -> "post";
            case "categories" -> "category";
            case "tags" -> "tag";
            case "comments" -> "comment";
            case "regions" -> "region";
            case "links" -> "link";
            case "email-channels" -> "email_channels";
            case "email-campaigns" -> "email_campaigns";
            case "email-deliveries" -> "email_deliveries";
            case "email-templates" -> "email_templates";
            case "audit-logs" -> "audit_logs";
            default -> segment.replace('-', '_');
        };
    }

    private static String actionFor(String method, String path, String resource) {
        if (path.endsWith("/original-download-ticket")) {
            return "media.download_original";
        }
        if (path.startsWith("/api/admin/security/content-access-tickets")) {
            return "security.ticket.rotate";
        }
        if (path.startsWith("/api/admin/security/media-download-events")) {
            return "media.download_audit.read";
        }
        if (path.startsWith("/api/admin/outbox") && path.endsWith("/replay")) {
            return "outbox.replay";
        }
        if (path.startsWith("/api/admin/outbox")) {
            return "GET".equals(method) ? "outbox.read" : "outbox.write";
        }
        if (path.startsWith("/api/admin/queues") && path.endsWith("/publish")) {
            return "queue.publish";
        }
        if (path.startsWith("/api/admin/posts") && path.endsWith("/publish")) {
            return "post.publish";
        }
        if (path.endsWith("/confirm")) {
            return "settings.confirm";
        }
        if (path.endsWith("/rollback")) {
            return "settings.rollback";
        }
        if ((path.startsWith("/api/admin/edge-nodes")
                || path.startsWith("/api/admin/node/certificate"))
                && (path.contains("issue-certificate") || path.endsWith("/generate"))) {
            return "edge.issue_certificate";
        }
        if (isEdgeCertificatePath(path) && path.contains("revoke")) {
            return "edge.revoke_certificate";
        }
        if (isDeadLetterPath(path) && (path.contains("/retry") || path.endsWith("/retry-batch"))) {
            return "dead_letter.replay";
        }
        if (path.startsWith("/api/admin/email-deliveries/") && path.endsWith("/retry")) {
            return "email_deliveries.retry";
        }
        if (path.equals("/api/admin/email-deliveries/fail-pending")) {
            return "email_deliveries.fail_pending";
        }
        if (path.startsWith("/api/admin/media/")
                && (path.endsWith("/retry") || path.endsWith("/batch-retry"))) {
            return "media.retry";
        }
        if (path.startsWith("/api/admin/media/") && path.endsWith("/virus-scan")) {
            return "media.scan";
        }
        if (path.startsWith("/api/admin/repost/") && path.endsWith("/revoke")) {
            return "repost.revoke";
        }
        if (path.startsWith("/api/admin/import")) {
            return "database.import";
        }
        if (path.startsWith("/api/admin/database")) {
            return path.endsWith("/migrate") ? "database.migrate" : "database.seed";
        }
        if (path.startsWith("/api/admin/settings")) {
            return "GET".equals(method) ? "settings.read" : "settings.write";
        }
        if ("GET".equals(method)) {
            return resource + ".read";
        }
        if ("DELETE".equals(method)) {
            return resource + ".delete";
        }
        return resource + ".write";
    }

    private static boolean isEdgeCertificatePath(String path) {
        return path.startsWith("/api/admin/edge-nodes")
                || path.startsWith("/api/admin/storage/edge-nodes")
                || path.startsWith("/api/admin/node/certificate");
    }

    private static boolean isDeadLetterPath(String path) {
        return path.startsWith("/api/admin/dead-letters")
                || path.startsWith("/api/admin/storage/dead-letter");
    }

    private static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    public record Decision(String resource, String action, boolean superAdminOnly,
                           boolean stepUpRequired, boolean idempotencyRequired) {
    }
}
