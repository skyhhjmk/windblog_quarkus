package com.biliwind.blog.service.settings;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * The single implementation of regional value resolution. The storage shape
 * differs per setting kind, so each kind has one accessor here instead of four
 * services inventing their own fallback chain.
 */
@ApplicationScoped
public class RegionalSettings {

    private final ConfigManager configManager;

    @Inject
    public RegionalSettings(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /** Exact object-override semantics: a regional object replaces the root object. */
    public JsonNode object(SettingKey key, BlogRegion region) {
        JsonNode value = configManager.get(key.key());
        if (value == null || !value.isObject()) {
            return value;
        }
        JsonNode regions = value.get("regions");
        String code = regionCode(region);
        if (!BlogRegion.GLOBAL.getCode().equals(code)
                && regions != null && regions.isObject()) {
            JsonNode regionalValue = regions.get(code);
            if (regionalValue != null && regionalValue.isObject()) {
                return regionalValue;
            }
        }
        ObjectNode globalValue = ((ObjectNode) value).deepCopy();
        globalValue.remove("regions");
        return globalValue;
    }

    /**
     * Language entries may be plain tags or objects with {@code default_language}.
     * A region inherits {@code regions.global}, then the root fallback.
     */
    public String language(BlogRegion region) {
        JsonNode value = configManager.get(SettingKey.SITE_LANGUAGE.key());
        BlogRegion effective = region == null ? BlogRegion.GLOBAL : region;
        String configured = regionLanguage(value, effective.getCode());
        if (configured == null && effective != BlogRegion.GLOBAL) {
            configured = regionLanguage(value, BlogRegion.GLOBAL.getCode());
        }
        if (configured == null && value != null) {
            configured = text(value.get("default_language"));
        }
        return configured;
    }

    /** Language from the L1 cache only; safe on the Vert.x IO thread. */
    public String languageCached(BlogRegion region) {
        JsonNode value = configManager.getLocal(SettingKey.SITE_LANGUAGE.key());
        BlogRegion effective = region == null ? BlogRegion.GLOBAL : region;
        String configured = regionLanguage(value, effective.getCode());
        if (configured == null && effective != BlogRegion.GLOBAL) {
            configured = regionLanguage(value, BlogRegion.GLOBAL.getCode());
        }
        if (configured == null && value != null) {
            configured = text(value.get("default_language"));
        }
        return configured;
    }

    public boolean hasLanguageOverride(BlogRegion region) {
        BlogRegion effective = region == null ? BlogRegion.GLOBAL : region;
        return regionLanguage(configManager.get(SettingKey.SITE_LANGUAGE.key()),
                effective.getCode()) != null;
    }

    /**
     * Favicon hierarchy: {@code favicon_url_regions.<code>}, then
     * {@code regions.<code>.favicon_url}, then the root favicon.
     */
    public String favicon(BlogRegion region) {
        String code = regionCode(region);
        if (!BlogRegion.GLOBAL.getCode().equals(code)) {
            JsonNode appearance = configManager.get(SettingKey.APPEARANCE.key());
            JsonNode regional = appearance == null ? null : appearance.path("favicon_url_regions").get(code);
            String value = regional == null ? "" : regional.asText("").trim();
            if (!value.isBlank()) {
                return value;
            }
            JsonNode objectRegion = object(SettingKey.APPEARANCE, region);
            String objectValue = objectRegion == null ? "" : objectRegion.path("favicon_url").asText("").trim();
            if (!objectValue.isBlank()) {
                return objectValue;
            }
        }
        return rootText(SettingKey.APPEARANCE, "favicon_url");
    }

    /** {@code site_url_regions.<code>}, then the root {@code site_url}. */
    public String siteUrl(BlogRegion region) {
        JsonNode siteInfo = configManager.get(SettingKey.SITE_INFO.key());
        String code = regionCode(region);
        if (!BlogRegion.GLOBAL.getCode().equals(code) && siteInfo != null) {
            String regional = siteInfo.path("site_url_regions").path(code).asText("").trim();
            if (!regional.isBlank()) {
                return regional;
            }
        }
        return siteInfo == null ? "" : siteInfo.path("site_url").asText("").trim();
    }

    /** Region-adjusted value for cache fingerprints; each kind stays visible. */
    public JsonNode resolved(SettingKey key, BlogRegion region) {
        SettingDefinition definition = SettingCatalogue.find(key);
        JsonNode raw = configManager.get(key.key());
        return switch (definition.regionalKind()) {
            case OBJECT_REGIONS -> object(key, region);
            case LANGUAGE -> textNode(language(region));
            case FAVICON -> textNode(favicon(region));
            case SITE_URL -> textNode(siteUrl(region));
            case NONE -> raw;
        };
    }

    private String rootText(SettingKey key, String field) {
        JsonNode value = configManager.get(key.key());
        return value == null ? "" : value.path(field).asText("").trim();
    }

    private static JsonNode textNode(String value) {
        return com.fasterxml.jackson.databind.node.TextNode.valueOf(value == null ? "" : value);
    }

    private static String regionCode(BlogRegion region) {
        return region == null ? BlogRegion.GLOBAL.getCode() : region.getCode().toLowerCase(Locale.ROOT);
    }

    private static String regionLanguage(JsonNode value, String regionCode) {
        if (value == null || regionCode == null) {
            return null;
        }
        JsonNode regions = value.get("regions");
        if (regions == null || !regions.isObject()) {
            return null;
        }
        JsonNode entry = regions.get(regionCode);
        if (entry == null) {
            return null;
        }
        if (entry.isTextual()) {
            return entry.asText();
        }
        if (entry.isObject()) {
            return text(entry.get("default_language"));
        }
        return null;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
