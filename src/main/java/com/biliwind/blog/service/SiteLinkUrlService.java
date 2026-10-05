package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.net.URI;

/** Resolves the public site URL used when checking friendly-link backlinks. */
@ApplicationScoped
public class SiteLinkUrlService {

    private final ConfigManager configManager;

    @Inject
    RegionContext regionContext;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    public SiteLinkUrlService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public String currentUrl() {
        BlogRegion region = BlogRegion.GLOBAL;
        try {
            if (regionContext != null && regionContext.getCurrentRegion() != null) {
                region = regionContext.getCurrentRegion();
            }
        } catch (ContextNotActiveException ignored) {
            // Scheduled and non-request callers use the default URL.
        }
        return urlFor(region);
    }

    public String urlFor(BlogRegion region) {
        JsonNode siteInfo = configManager.get("site_info");
        BlogRegion selectedRegion = region == null ? BlogRegion.GLOBAL : region;
        if (selectedRegion != BlogRegion.GLOBAL) {
            String regionalUrl = siteInfo == null ? ""
                    : siteInfo.path("site_url_regions").path(selectedRegion.getCode()).asText("");
            String normalizedRegionalUrl = normalizeConfiguredUrl(regionalUrl);
            if (!normalizedRegionalUrl.isEmpty()) {
                return normalizedRegionalUrl;
            }
        }

        String configuredUrl = siteInfo == null ? "" : siteInfo.path("site_url").asText("");
        String normalizedConfiguredUrl = normalizeConfiguredUrl(configuredUrl);
        return normalizedConfiguredUrl.isEmpty() ? publicUrlService.getBaseUrl() : normalizedConfiguredUrl;
    }

    private String normalizeConfiguredUrl(String value) {
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
            return PublicUrlService.normalize(uri.toASCIIString());
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
