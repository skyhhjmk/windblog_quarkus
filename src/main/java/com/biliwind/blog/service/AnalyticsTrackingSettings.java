package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import java.net.URI;

/** Validated, database-backed public analytics embed configuration. */
@ApplicationScoped
public class AnalyticsTrackingSettings {
    private static final String KEY = "analytics_tracking";

    private final ConfigManager configManager;

    @Inject
    RegionContext regionContext;

    public AnalyticsTrackingSettings(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Settings current() {
        BlogRegion region = BlogRegion.GLOBAL;
        try {
            if (regionContext != null && regionContext.getCurrentRegion() != null) {
                region = regionContext.getCurrentRegion();
            }
        } catch (ContextNotActiveException ignored) {
            // Non-request callers retain the legacy global configuration.
        }
        return current(region);
    }

    public Settings current(BlogRegion region) {
        JsonNode value = configManager.get(KEY);
        if (value == null || !value.isObject()) return Settings.disabled();
        BlogRegion selectedRegion = region == null ? BlogRegion.GLOBAL : region;
        if (selectedRegion != BlogRegion.GLOBAL) {
            JsonNode regionalSettings = value.path("regions");
            String regionCode = selectedRegion.getCode();
            if (regionalSettings.isObject() && regionalSettings.has(regionCode)) {
                value = regionalSettings.get(regionCode);
            }
        }
        return parse(value);
    }

    private Settings parse(JsonNode value) {
        if (value == null || !value.path("enabled").asBoolean(false)) return Settings.disabled();
        String scriptUrl = value.path("scriptUrl").asText("").trim();
        String siteId = value.path("siteId").asText("").trim();
        if (scriptUrl.isEmpty() || siteId.isEmpty()) return Settings.disabled();
        try {
            URI uri = URI.create(scriptUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()) && isLocalOrTailscale(uri.getHost()))
                    || uri.getHost() == null) return Settings.disabled();
            return new Settings(
                    true,
                    uri.toString(),
                    uri.getScheme() + "://" + uri.getAuthority(),
                    siteId,
                    value.path("requireConsent").asBoolean(false),
                    value.path("heatmaps").asBoolean(true),
                    value.path("webVitals").asBoolean(false),
                    value.path("forms").asBoolean(false),
                    value.path("media").asBoolean(false),
                    value.path("errors").asBoolean(false),
                    value.path("tagManager").asBoolean(false),
                    value.path("experiments").asBoolean(false),
                    value.path("privacyPreferences").asBoolean(true));
        } catch (IllegalArgumentException ignored) {
            return Settings.disabled();
        }
    }

    private static boolean isLocalOrTailscale(String host) {
        if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) return true;
        String[] parts = host == null ? new String[0] : host.split("\\.");
        if (parts.length != 4) return false;
        try {
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);
            return first == 100 && second >= 64 && second <= 127
                    && java.util.Arrays.stream(parts).allMatch(part -> {
                        try {
                            int value = Integer.parseInt(part);
                            return value >= 0 && value <= 255;
                        } catch (NumberFormatException ignored) {
                            return false;
                        }
                    });
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public record Settings(
            boolean enabled,
            String scriptUrl,
            String origin,
            String siteId,
            boolean requireConsent,
            boolean heatmaps,
            boolean webVitals,
            boolean forms,
            boolean media,
            boolean errors,
            boolean tagManager,
            boolean experiments,
            boolean privacyPreferences) {
        public static Settings disabled() {
            return new Settings(false, "", "", "", false, false, false, false, false, false, false, false, false);
        }
    }
}
