package com.biliwind.blog.service;

import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
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

    private static SettingsReader settings() {
        return Arc.container().instance(SettingsReader.class).get();
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

    private static String regionalString(SettingKey key, String field) {
        return settings().regionalText(key, field, currentRegion());
    }

    public static String baseUrl() {
        try {
            PublicUrlService service = Arc.container().instance(PublicUrlService.class).get();
            if (service != null) {
                return service.getBaseUrl();
            }
        } catch (RuntimeException ignored) {
            // Unit tests and startup callers fall back to deployment settings.
        }
        return PublicUrlService.normalize(
                org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getOptionalValue("windblog.site.public-url", String.class)
                        .orElse("http://localhost:8080"));
    }

    public static String siteTitle() {
        return regionalString(SettingKey.SITE_INFO, "title");
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
        return regionalString(SettingKey.SITE_INFO, "subtitle");
    }

    public static String siteDescription() {
        return regionalString(SettingKey.SITE_INFO, "description");
    }

    public static String siteKeywords() {
        JsonNode siteInfoNode = settings().regionalObject(SettingKey.SITE_INFO, currentRegion());
        return siteInfoNode == null ? "" : keywordsText(siteInfoNode.get("keywords"));
    }

    /**
     * Migration 045 seeded a comma-separated string; newer installs store an
     * array. Both shapes are valid and must render identically.
     */
    static String keywordsText(JsonNode keywords) {
        if (keywords == null) {
            return "";
        }
        if (keywords.isTextual()) {
            return keywords.asText();
        }
        if (!keywords.isArray()) {
            return "";
        }
        StringBuilder keywordsText = new StringBuilder();
        for (JsonNode keywordNode : keywords) {
            if (!keywordsText.isEmpty()) {
                keywordsText.append(", ");
            }
            keywordsText.append(keywordNode.asText());
        }
        return keywordsText.toString();
    }

    public static String copyright() {
        return regionalString(SettingKey.SITE_FOOTER, "copyright");
    }

    public static String icp() {
        return regionalString(SettingKey.SITE_FOOTER, "icp");
    }

    public static String icpUrl() {
        return safeExternalHttpUrl(regionalString(SettingKey.SITE_FOOTER, "icp_url"));
    }

    public static String publicSecurityRecord() {
        return regionalString(SettingKey.SITE_FOOTER, "public_security_record");
    }

    public static String publicSecurityRecordUrl() {
        return safeExternalHttpUrl(regionalString(SettingKey.SITE_FOOTER, "public_security_record_url"));
    }

    public static boolean footerRecordsVisible() {
        String configuredRegion = regionalString(SettingKey.SITE_FOOTER, "record_display_region");
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
        return regionalString(SettingKey.SITE_FOOTER, "custom_html");
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
        return settings().flag(SettingKey.LEGAL_PRIVACY, "showAnalyticsOptOut")
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

    public static String adsenseConfig() {
        JsonNode value = settings().object(SettingKey.GOOGLE_ADSENSE);
        return value == null ? "{}" : value.toString();
    }

    public static String googleAdsensePublisherId() {
        JsonNode value = settings().object(SettingKey.GOOGLE_ADSENSE);
        String clientId = value == null ? "" : value.path("clientId").asText("").trim();
        return clientId.matches("ca-pub-\\d{16}") ? clientId : "";
    }

    public static String logoUrl() {
        return settings().text(SettingKey.APPEARANCE, "logo_url");
    }

    public static String faviconUrl() {
        try {
            FaviconResolverService resolver = Arc.container().instance(FaviconResolverService.class).get();
            if (resolver != null) {
                return resolver.resolveFaviconUrl();
            }
        } catch (Exception ignored) {}
        return settings().favicon(currentRegion());
    }

    public static String themeColor() {
        return settings().text(SettingKey.APPEARANCE, "theme_color");
    }

    public static String social(String platform) {
        return settings().text(SettingKey.SOCIAL_LINKS, platform);
    }

    public static boolean toggle(String key) {
        return settings().flag(SettingKey.FEATURE_TOGGLES, key);
    }
    public static String googleSiteVerification() {
        return settings().text(SettingKey.SEO, "google_site_verification");
    }
    public static boolean internalLinkGraphEnabled() {
        return settings().flag(SettingKey.SEO, "internal_link_graph_enabled");
    }
}
