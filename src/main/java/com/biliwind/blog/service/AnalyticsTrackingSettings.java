package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;

/** Validated, database-backed public analytics embed configuration. */
@ApplicationScoped
public class AnalyticsTrackingSettings {
    private static final String KEY = "analytics_tracking";

    private final ConfigManager configManager;

    public AnalyticsTrackingSettings(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Settings current() {
        JsonNode value = configManager.get(KEY);
        if (value == null || !value.path("enabled").asBoolean(false)) return Settings.disabled();
        String scriptUrl = value.path("scriptUrl").asText("").trim();
        String siteId = value.path("siteId").asText("").trim();
        if (scriptUrl.isEmpty() || siteId.isEmpty()) return Settings.disabled();
        try {
            URI uri = URI.create(scriptUrl);
            if (!("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme())
                            && ("localhost".equalsIgnoreCase(uri.getHost())
                                    || "127.0.0.1".equals(uri.getHost())))
                    || uri.getHost() == null) return Settings.disabled();
            return new Settings(true, uri.toString(), uri.getScheme() + "://" + uri.getAuthority(), siteId);
        } catch (IllegalArgumentException ignored) {
            return Settings.disabled();
        }
    }

    public record Settings(boolean enabled, String scriptUrl, String origin, String siteId) {
        public static Settings disabled() {
            return new Settings(false, "", "", "");
        }
    }
}
