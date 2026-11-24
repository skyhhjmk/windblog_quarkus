package com.biliwind.blog.service.security;

import com.biliwind.blog.service.settings.SettingCatalogue;
import com.biliwind.blog.service.settings.SettingDefinition;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Database-backed HTTP security header and health-endpoint exposure policy.
 * Reads from {@link SettingKey#SECURITY_HTTP} so administrators can adjust
 * CSP enforcement, frame protection, and /q/health visibility at runtime.
 */
@ApplicationScoped
public class SecurityHttpSettings {

    public static final String XFO_DENY = "DENY";
    public static final String XFO_SAMEORIGIN = "SAMEORIGIN";
    public static final String DISABLED = "DISABLED";

    public static final String HEALTH_MODE_SUMMARY = "summary";
    public static final String HEALTH_MODE_PRIVATE_ONLY = "private_only";
    public static final String HEALTH_MODE_DETAILED = "detailed";

    private static final Set<String> ALLOWED_REFERRER_POLICIES = Set.of(
            "strict-origin-when-cross-origin",
            "no-referrer",
            "same-origin",
            "strict-origin",
            "origin",
            "origin-when-cross-origin",
            "no-referrer-when-downgrade"
    );

    @Inject
    SettingsReader settings;

    public Snapshot current() {
        JsonNode node = settings == null ? null : settings.object(SettingKey.SECURITY_HTTP);
        return fromNode(node);
    }

    /** L1-only read safe on the Vert.x IO thread. */
    public Snapshot currentLocal() {
        JsonNode node = settings == null ? null : settings.localRaw(SettingKey.SECURITY_HTTP);
        return fromNode(node);
    }

    public static Snapshot defaults() {
        return fromNode(null);
    }

    public static Snapshot fromNode(JsonNode node) {
        SettingDefinition definition = SettingCatalogue.find(SettingKey.SECURITY_HTTP);
        boolean hstsEnabled = readBoolean(node, definition, "hsts_enabled");
        boolean cspEnabled = readBoolean(node, definition, "csp_enabled");
        boolean cspEnforce = readBoolean(node, definition, "csp_enforce");
        boolean cspTrustedTypesEnabled = readBoolean(node, definition, "csp_trusted_types_enabled");
        String cspImgSources = readText(node, definition, "csp_img_sources");
        String cspConnectSources = readText(node, definition, "csp_connect_sources");
        String xFrameOptions = normalizeXFrameOptions(readText(node, definition, "x_frame_options"));
        boolean xContentTypeOptionsNosniff = readBoolean(node, definition, "x_content_type_options_nosniff");
        String referrerPolicy = normalizeReferrerPolicy(readText(node, definition, "referrer_policy"));
        String permissionsPolicy = sanitizeHeaderValue(readText(node, definition, "permissions_policy"));
        String healthPublicMode = normalizeHealthPublicMode(readText(node, definition, "health_public_mode"));
        List<String> healthDetailedCidrs = parseCidrs(readText(node, definition, "health_detailed_cidrs"));

        return new Snapshot(
                hstsEnabled,
                cspEnabled,
                cspEnforce,
                cspTrustedTypesEnabled,
                cspImgSources,
                cspConnectSources,
                xFrameOptions,
                xContentTypeOptionsNosniff,
                referrerPolicy,
                permissionsPolicy,
                healthPublicMode,
                List.copyOf(healthDetailedCidrs)
        );
    }

    public static boolean matchesAnyCidr(String ipLiteral, List<String> cidrs) {
        if (ipLiteral == null || ipLiteral.isBlank() || cidrs == null || cidrs.isEmpty()) {
            return false;
        }
        InetAddress address = parseAddress(ipLiteral);
        if (address == null) {
            return false;
        }
        for (String cidr : cidrs) {
            if (matchesCidr(address, cidr)) {
                return true;
            }
        }
        return false;
    }

    private static boolean readBoolean(JsonNode node, SettingDefinition definition, String field) {
        JsonNode fieldNode = node != null && node.isObject() ? node.get(field) : null;
        if (fieldNode != null) {
            if (fieldNode.isBoolean()) {
                return fieldNode.asBoolean();
            }
            if (fieldNode.isTextual()) {
                String text = fieldNode.asText().trim();
                if ("true".equalsIgnoreCase(text)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(text)) {
                    return false;
                }
            }
        }
        JsonNode fallback = definition.defaultField(field);
        return fallback != null && fallback.isBoolean() && fallback.asBoolean();
    }

    private static String readText(JsonNode node, SettingDefinition definition, String field) {
        JsonNode fieldNode = node != null && node.isObject() ? node.get(field) : null;
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        JsonNode fallback = definition.defaultField(field);
        return fallback != null && fallback.isTextual() ? fallback.asText() : "";
    }

    private static String normalizeXFrameOptions(String value) {
        if (value == null || value.isBlank()) {
            return XFO_DENY;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        if (XFO_SAMEORIGIN.equals(upper) || DISABLED.equals(upper)) {
            return upper;
        }
        return XFO_DENY;
    }

    private static String normalizeReferrerPolicy(String value) {
        if (value == null || value.isBlank()) {
            return "strict-origin-when-cross-origin";
        }
        String trimmed = value.trim();
        if (DISABLED.equalsIgnoreCase(trimmed)) {
            return DISABLED;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return ALLOWED_REFERRER_POLICIES.contains(lower) ? lower : "strict-origin-when-cross-origin";
    }

    private static String normalizeHealthPublicMode(String value) {
        if (value == null || value.isBlank()) {
            return HEALTH_MODE_SUMMARY;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (HEALTH_MODE_PRIVATE_ONLY.equals(lower) || HEALTH_MODE_DETAILED.equals(lower)) {
            return lower;
        }
        return HEALTH_MODE_SUMMARY;
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace("\r", "").replace("\n", "").trim();
        return cleaned.length() > 512 ? cleaned.substring(0, 512) : cleaned;
    }

    private static List<String> parseCidrs(String raw) {
        List<String> cidrs = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return cidrs;
        }
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                cidrs.add(part.trim());
            }
        }
        return cidrs;
    }

    private static boolean matchesCidr(InetAddress address, String cidr) {
        if (cidr == null || cidr.isBlank()) {
            return false;
        }
        String[] parts = cidr.split("/", 2);
        InetAddress networkAddress = parseAddress(parts[0]);
        if (networkAddress == null || networkAddress.getAddress().length != address.getAddress().length) {
            return false;
        }
        int maxPrefixLength = address.getAddress().length * 8;
        int prefixLength = maxPrefixLength;
        if (parts.length == 2) {
            try {
                prefixLength = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (prefixLength < 0 || prefixLength > maxPrefixLength) {
            return false;
        }
        byte[] addressBytes = address.getAddress();
        byte[] networkBytes = networkAddress.getAddress();
        int remainingBits = prefixLength;
        for (int i = 0; i < addressBytes.length; i++) {
            int comparedBits = Math.min(8, remainingBits);
            if (comparedBits == 0) {
                return true;
            }
            int mask = 255 << (8 - comparedBits);
            if ((addressBytes[i] & mask) != (networkBytes[i] & mask)) {
                return false;
            }
            remainingBits -= comparedBits;
        }
        return true;
    }

    private static InetAddress parseAddress(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = ClientIpResolver.normalizeCandidate(value);
        if (!ClientIpResolver.isAddressLiteral(normalized)) {
            return null;
        }
        try {
            return InetAddress.getByName(normalized);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record Snapshot(
            boolean hstsEnabled,
            boolean cspEnabled,
            boolean cspEnforce,
            boolean cspTrustedTypesEnabled,
            String cspImgSources,
            String cspConnectSources,
            String xFrameOptions,
            boolean xContentTypeOptionsNosniff,
            String referrerPolicy,
            String permissionsPolicy,
            String healthPublicMode,
            List<String> healthDetailedCidrs
    ) {
        public String frameAncestorsDirective() {
            if (XFO_SAMEORIGIN.equals(xFrameOptions)) {
                return "'self'";
            }
            return "'none'";
        }
    }
}
