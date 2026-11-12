package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.settings.SettingCatalogue;
import com.biliwind.blog.service.settings.SettingDefinition;
import com.biliwind.blog.service.settings.SettingSecrets;

import java.util.Locale;

/** Central resource/action mapping for the administrator authorization boundary. */
public final class AdminAuthorizationPolicy {

    /**
     * Risk is intentionally independent from role and request de-duplication.
     * A normal administrator action must not become a password prompt merely
     * because it is a write request.
     */
    public enum RiskLevel {
        LOW,
        DAILY,
        HIGH
    }

    private AdminAuthorizationPolicy() {
    }

    public static Decision decide(String method, String path) {
        String normalizedMethod = normalizeMethod(method);
        String normalizedPath = normalizePath(path);
        String resource = resourceFor(normalizedPath);
        String action = actionFor(normalizedMethod, normalizedPath, resource);
        RiskLevel riskLevel = riskFor(normalizedMethod, normalizedPath, action);
        boolean superAdminOnly = isSuperAdminOnly(normalizedMethod, normalizedPath, riskLevel);
        boolean stepUpRequired = riskLevel == RiskLevel.HIGH;
        boolean idempotencyRequired = requiresIdempotency(normalizedMethod, normalizedPath, riskLevel);
        return new Decision(resource, action, riskLevel, superAdminOnly, stepUpRequired, idempotencyRequired);
    }

    public static boolean requiresStepUp(String method, String path) {
        return decide(method, path).stepUpRequired();
    }

    public static boolean requiresIdempotencyKey(String method, String path) {
        return decide(method, path).idempotencyRequired();
    }

    private static boolean isSuperAdminOnly(String method, String path, RiskLevel riskLevel) {
        if (path.startsWith("/api/admin/security/honeypot")) {
            return true;
        }
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
        if (path.startsWith("/api/admin/settings") && riskLevel == RiskLevel.HIGH) {
            return true;
        }
        if (path.startsWith("/api/admin/permissions/roles") && !"GET".equals(method)) {
            return true;
        }
        return false;
    }

    private static RiskLevel riskFor(String method, String path, String action) {
        if ("OPTIONS".equals(method)) {
            return RiskLevel.LOW;
        }
        if (isHighRiskAction(method, path, action)) {
            return RiskLevel.HIGH;
        }
        if ("GET".equals(method)) {
            return RiskLevel.LOW;
        }
        return RiskLevel.DAILY;
    }

    private static boolean isHighRiskAction(String method, String path, String action) {
        if (path.startsWith("/api/admin/storage/sync/restore/") && "POST".equals(method)) {
            return true;
        }
        if (path.endsWith("/deployment-zip") || "media.download_original".equals(action)) {
            return true;
        }
        if (path.startsWith("/api/admin/security/content-access-tickets")
                || path.startsWith("/api/admin/database")
                || path.startsWith("/api/admin/import")
                || path.startsWith("/api/admin/node/certificate")
                || path.startsWith("/api/admin/edge-nodes")
                || path.startsWith("/api/admin/storage/edge-nodes")
                || path.startsWith("/api/admin/permissions/roles")) {
            return !"GET".equals(method);
        }
        if (path.startsWith("/api/admin/settings/") && !"GET".equals(method)) {
            if (path.equals("/api/admin/settings/apply-audit-value")) {
                return true;
            }
            return isSensitiveSettingKey(path);
        }
        if (path.startsWith("/api/admin/users/") && (path.endsWith("/wallet/adjust")
                || path.endsWith("/wallet/check-in-reward"))) {
            return "POST".equals(method);
        }
        if (path.startsWith("/api/admin/users/") && path.endsWith("/inventory/grant")) {
            return "POST".equals(method);
        }
        if (path.equals("/api/admin/email-deliveries/fail-pending")) {
            return "POST".equals(method);
        }
        if ("POST".equals(method) && path.equals("/api/admin/email-campaigns")) {
            return true;
        }
        if (path.startsWith("/api/admin/repost/") && path.endsWith("/revoke")) {
            return "POST".equals(method);
        }
        if (path.startsWith("/api/admin/repost/users/") && path.endsWith("/ban")) {
            return "POST".equals(method);
        }
        return path.equals("/api/admin/system/decrypt-error")
                || path.equals("/api/admin/system/sync-cluster-keys");
    }

    private static boolean requiresIdempotency(String method, String path, RiskLevel riskLevel) {
        if ("GET".equals(method) || "OPTIONS".equals(method)) {
            return false;
        }
        if (riskLevel == RiskLevel.HIGH) {
            return true;
        }
        if (path.startsWith("/api/admin/ai/topics/") && path.endsWith("/runs")) {
            return true;
        }
        return path.endsWith("/publish") || path.contains("/replay") || path.endsWith("/retry")
                || path.endsWith("/batch-retry") || path.endsWith("/test")
                || path.endsWith("/draft") || path.endsWith("/draft/regenerate");
    }

    private static boolean isSensitiveSettingKey(String path) {
        String prefix = "/api/admin/settings/";
        String remainder = path.substring(prefix.length());
        int slash = remainder.indexOf('/');
        String key = slash >= 0 ? remainder.substring(0, slash) : remainder;
        SettingDefinition definition = SettingCatalogue.findByKey(key);
        if (definition != null) {
            return definition.requiresVerification()
                    || definition.secret()
                    || !definition.secretFields().isEmpty();
        }
        // Keys outside the database catalogue (for example mail credentials
        // stored by another subsystem) still fail closed on the shared
        // secret-field predicate used by masking.
        return SettingSecrets.isSecretFieldName(key);
    }

    private static String resourceFor(String path) {
        if (path.startsWith("/api/admin/seo/posts/") || path.startsWith("/api/admin/seo/link-graph")) return "post";
        if (path.startsWith("/api/admin/seo")) return "settings";
        if (path.startsWith("/api/admin/security/honeypot")) {
            return "honeypot";
        }
        if (path.startsWith("/api/admin/users/") && path.contains("/inventory")) {
            return "inventory";
        }
        if (path.startsWith("/api/admin/users/") && path.contains("/wallet")) {
            return "wallet";
        }
        if (path.startsWith("/api/admin/security/media-download-events")) {
            return "media";
        }
        if (path.startsWith("/api/admin/media")) {
            return "media";
        }
        if (path.startsWith("/api/admin/posts") || path.startsWith("/api/admin/translations")) {
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
        if (path.startsWith("/api/admin/security/honeypot")) {
            return "GET".equals(method) ? "honeypot.read" : "honeypot.rule.update";
        }
        if (path.startsWith("/api/admin/users/") && path.endsWith("/inventory/grant")) {
            return "inventory.grant";
        }
        if (path.endsWith("/original-download-ticket")) {
            return "media.download_original";
        }
        if (path.equals("/api/admin/edge-nodes/connect")) {
            return "edge.connection.connect";
        }
        if (path.equals("/api/admin/edge-nodes/connection/bootstrap")) {
            return "edge.connection.bootstrap";
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
        return canonicalPath(path);
    }

    /**
     * Canonical form used by every admin authentication and authorization
     * check. JAX-RS still routes "/x/" and "//x" to "/x", so string rules must
     * see the same path the router matched or a trailing slash skips them.
     */
    public static String canonicalPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String collapsed = ("/" + path).replaceAll("/{2,}", "/");
        while (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    /**
     * Raw request paths the admin boundary refuses outright: matrix parameters,
     * encoded separators or dots, backslashes and dot segments can make the
     * routed resource differ from the string the policy inspects.
     */
    public static boolean isAmbiguousRawPath(String rawPath) {
        if (rawPath == null) {
            return false;
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.indexOf(';') >= 0 || lower.indexOf('\\') >= 0
                || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")
                || lower.contains("%3b") || lower.contains("%25")) {
            return true;
        }
        for (String segment : lower.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when an admin request carrying an ambiguous raw path must be
     * refused before routing. The check runs on the raw, still percent-encoded
     * path from Vert.x: JAX-RS normalises the request URI, so an encoded dot
     * segment would otherwise be resolved (and routed) before any filter sees
     * it. Both the raw and the decoded spelling are inspected so that a
     * percent-encoded "/api/%61dmin" cannot slip past the prefix test.
     */
    public static boolean rejectsAmbiguousAdminPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || !isAmbiguousRawPath(rawPath)) {
            return false;
        }
        if (isAdminRawPath(rawPath)) {
            return true;
        }
        try {
            return isAdminRawPath(java.net.URLDecoder.decode(rawPath, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isAdminRawPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.equals("/api/admin") || lower.startsWith("/api/admin/");
    }

    public record Decision(String resource, String action, RiskLevel riskLevel, boolean superAdminOnly,
                           boolean stepUpRequired, boolean idempotencyRequired) {
    }
}
