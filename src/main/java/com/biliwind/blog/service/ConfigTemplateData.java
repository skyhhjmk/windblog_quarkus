package com.biliwind.blog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import io.quarkus.arc.Arc;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@TemplateData(namespace = "config")
@ApplicationScoped
public class ConfigTemplateData {

    private static ConfigManager configManager() {
        return Arc.container().instance(ConfigManager.class).get();
    }

    private static BlogRegion currentRegion() {
        try {
            RegionContext context = jakarta.enterprise.inject.spi.CDI.current()
                    .select(RegionContext.class).get();
            return context.getCurrentRegion();
        } catch (RuntimeException ignored) {
            return BlogRegion.GLOBAL;
        }
    }

    private static JsonNode regionalSetting(String key) {
        return configManager().getForRegion(key, currentRegion());
    }

    private static String regionalString(String key, String field, String defaultValue) {
        return configManager().getStringForRegion(key, field, defaultValue, currentRegion());
    }

    public static String baseUrl() {
        return PublicUrlService.normalize(
                org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getOptionalValue("windblog.site.public-url", String.class)
                        .orElse("http://localhost:8080"));
    }

    public static String siteTitle() {
        return regionalString("site_info", "title", "WindBlog");
    }

    public static String siteNavRoot() {
        String title = siteTitle();
        return "~/" + (title == null || title.isBlank() ? "WindBlog" : title);
    }

    public static String siteTitleInitial() {
        String siteTitle = siteTitle();
        if (siteTitle == null) {
            return "W";
        }

        if (siteTitle.isBlank()) {
            return "W";
        }

        return siteTitle.substring(0, 1);
    }

    public static String siteSubtitle() {
        return regionalString("site_info", "subtitle", "");
    }

    public static String siteDescription() {
        return regionalString("site_info", "description", "");
    }

    public static String siteKeywords() {
        JsonNode siteInfoNode = regionalSetting("site_info");
        if (siteInfoNode != null && siteInfoNode.has("keywords") && siteInfoNode.get("keywords").isArray()) {
            StringBuilder keywordsText = new StringBuilder();
            for (JsonNode keywordNode : siteInfoNode.get("keywords")) {
                if (!keywordsText.isEmpty()) {
                    keywordsText.append(", ");
                }
                keywordsText.append(keywordNode.asText());
            }
            return keywordsText.toString();
        }
        return "";
    }

    public static String copyright() {
        return regionalString("site_footer", "copyright", "© 2026 WindBlog. Built with Quarkus.");
    }

    public static String icp() {
        return regionalString("site_footer", "icp", "");
    }

    public static String icpUrl() {
        return safeExternalHttpUrl(regionalString("site_footer", "icp_url", ""));
    }

    public static String publicSecurityRecord() {
        return regionalString("site_footer", "public_security_record", "");
    }

    public static String publicSecurityRecordUrl() {
        return safeExternalHttpUrl(regionalString("site_footer", "public_security_record_url", ""));
    }

    public static boolean footerRecordsVisible() {
        String configuredRegion = regionalString("site_footer", "record_display_region", "all");
        RegionContext regionContext = jakarta.enterprise.inject.spi.CDI.current()
                .select(RegionContext.class).get();
        return isFooterRecordsVisible(configuredRegion, regionContext.getCurrentRegion());
    }

    static boolean isFooterRecordsVisible(String configuredRegion, BlogRegion currentRegion) {
        if (configuredRegion == null || configuredRegion.isBlank()
                || "all".equalsIgnoreCase(configuredRegion.trim())) {
            return true;
        }
        if (currentRegion == null) {
            return false;
        }
        String normalizedRegion = configuredRegion.trim().toLowerCase(Locale.ROOT);
        boolean knownRegion = Arrays.stream(BlogRegion.values())
                .anyMatch(region -> region.getCode().equals(normalizedRegion));
        return knownRegion && currentRegion.getCode().equals(normalizedRegion);
    }

    static String safeExternalHttpUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return "";
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    public static String footerHtml() {
        return regionalString("site_footer", "custom_html", "");
    }

    public static List<RegionRuleService.SiteDomainLink> siteDomains() {
        return Arc.container().instance(RegionRuleService.class).get().siteDomains();
    }

    public static boolean analyticsTrackingEnabled() {
        return analyticsTrackingSettings().enabled();
    }

    public static String analyticsTrackingScriptUrl() {
        return analyticsTrackingSettings().scriptUrl();
    }

    public static String analyticsTrackingSiteId() {
        return analyticsTrackingSettings().siteId();
    }

    public static boolean analyticsTrackingRequireConsent() {
        return analyticsTrackingSettings().requireConsent();
    }

    public static String analyticsTrackingConsentScriptUrl() {
        AnalyticsTrackingSettings.Settings settings = analyticsTrackingSettings();
        return settings.origin().isBlank() ? "" : settings.origin() + "/consent.js";
    }

    public static boolean analyticsTrackingHeatmapsEnabled() {
        return analyticsTrackingSettings().heatmaps();
    }

    public static boolean analyticsTrackingWebVitalsEnabled() {
        return analyticsTrackingSettings().webVitals();
    }

    public static boolean analyticsTrackingFormsEnabled() {
        return analyticsTrackingSettings().forms();
    }

    public static boolean analyticsTrackingMediaEnabled() {
        return analyticsTrackingSettings().media();
    }

    public static boolean analyticsTrackingErrorsEnabled() {
        return analyticsTrackingSettings().errors();
    }

    public static boolean analyticsTrackingTagManagerEnabled() {
        return analyticsTrackingSettings().tagManager();
    }

    public static boolean analyticsTrackingExperimentsEnabled() {
        return analyticsTrackingSettings().experiments();
    }

    public static boolean analyticsTrackingPrivacyPreferencesEnabled() {
        AnalyticsTrackingSettings.Settings settings = analyticsTrackingSettings();
        return settings.privacyPreferences() && !settings.origin().isBlank() && !settings.siteId().isBlank();
    }

    public static boolean legalPrivacyAnalyticsOptOutEnabled() {
        return configManager().getBoolean("legal_privacy", "showAnalyticsOptOut", false)
                && analyticsTrackingPrivacyPreferencesAvailable();
    }

    public static String legalPrivacyAnalyticsOptOutUrl() {
        return analyticsTrackingPrivacyPreferencesAvailable()
                ? analyticsTrackingPrivacyPreferencesUrl()
                : "";
    }

    private static boolean analyticsTrackingPrivacyPreferencesAvailable() {
        AnalyticsTrackingSettings.Settings settings = analyticsTrackingSettings();
        return !settings.origin().isBlank() && !settings.siteId().isBlank();
    }

    public static String analyticsTrackingPrivacyPreferencesUrl() {
        AnalyticsTrackingSettings.Settings settings = analyticsTrackingSettings();
        return settings.origin().isBlank() || settings.siteId().isBlank()
                ? ""
                : settings.origin() + "/privacy/preferences?siteId=" + java.net.URLEncoder.encode(
                        settings.siteId(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static AnalyticsTrackingSettings.Settings analyticsTrackingSettings() {
        return Arc.container().instance(AnalyticsTrackingSettings.class).get().current();
    }

    public static String logoUrl() {
        return configManager().getString("appearance", "logo_url", "/logo.png");
    }

    public static String faviconUrl() {
        return configManager().getString("appearance", "favicon_url", "/favicon.ico");
    }

    public static String themeColor() {
        return configManager().getString("appearance", "theme_color", "#0A0C10");
    }

    public static String social(String platform) {
        return configManager().getString("social_links", platform, "");
    }

    public static boolean toggle(String key) {
        return configManager().getBoolean("feature_toggles", key, true);
    }
}
