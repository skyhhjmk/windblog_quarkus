package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the appropriate favicon URL for the current visitor's region.
 * Automatically adapts media storage class URLs, CDN endpoints, and regional domains.
 */
@ApplicationScoped
public class FaviconResolverService {

    @Inject
    ConfigManager configManager;

    @Inject
    RegionContext regionContext;

    @Inject
    RegionRuleService regionRuleService;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    StorageService storageService;

    /**
     * Resolves the favicon URL for the active request's region.
     * Returns empty string if no favicon is configured, allowing fallback to default vector SVG.
     */
    public String resolveFaviconUrl() {
        return resolveFaviconUrlForRegion(getCurrentRegion());
    }

    public String resolveFaviconUrlForRegion(BlogRegion region) {
        String raw = getConfiguredFaviconUrl(region);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return adaptFaviconUrl(raw.trim(), region);
    }

    public BlogRegion getCurrentRegion() {
        if (regionContext != null) {
            try {
                BlogRegion region = regionContext.getCurrentRegion();
                if (region != null) {
                    return region;
                }
            } catch (Exception ignored) {
                // Outside active request context
            }
        }
        return BlogRegion.GLOBAL;
    }

    /**
     * Retrieves the raw configured favicon URL, preferring region-specific overrides.
     */
    public String getConfiguredFaviconUrl(BlogRegion region) {
        String regionCode = region == null ? "global" : region.getCode().toLowerCase(Locale.ROOT);

        // 1. Check appearance.favicon_url_regions.<region>
        JsonNode appearance = configManager.get("appearance");
        if (appearance != null && appearance.has("favicon_url_regions")) {
            JsonNode regNode = appearance.get("favicon_url_regions");
            if (regNode.has(regionCode)) {
                String val = regNode.get(regionCode).asText("");
                if (!val.isBlank()) {
                    return val.trim();
                }
            }
        }

        // 2. Check appearance.regions.<region>.favicon_url
        String forRegion = configManager.getStringForRegion("appearance", "favicon_url", "", region);
        if (forRegion != null && !forRegion.isBlank()) {
            return forRegion.trim();
        }

        // 3. Fallback to default appearance.favicon_url
        return configManager.getString("appearance", "favicon_url", "").trim();
    }

    /**
     * Automatically adapts the raw favicon URL according to the target region.
     * 1. If it points to an uploaded media, resolves the optimal storage class/CDN URL for the region.
     * 2. If it points to an internal site domain, rewrites the host to the target region's site domain.
     * 3. External third-party URLs remain untouched.
     */
    public String adaptFaviconUrl(String rawUrl, BlogRegion region) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        String trimmed = rawUrl.trim();

        // 1. Try finding Media entity if it points to an upload
        String uploadKey = extractUploadKey(trimmed);
        if (uploadKey != null && !uploadKey.isBlank()) {
            try {
                Media media = Media.find("storageKey = ?1 and deletedAt is null", uploadKey).firstResult();
                if (media == null) {
                    media = Media.find("fileName = ?1 and deletedAt is null", uploadKey).firstResult();
                }
                if (media != null && media.storageClasses != null) {
                    String bestUrl = storageService.getBestAccessUrl(media, VariantType.ORIGINAL, region);
                    if (bestUrl != null && !bestUrl.isBlank()) {
                        return bestUrl;
                    }
                }
            } catch (Exception ignored) {
                // If database or storage lookup fails, fall through
            }
        }

        // 2. If it's an absolute URL pointing to an internal site domain, rewrite to the regional domain
        try {
            URI uri = URI.create(trimmed);
            String host = uri.getHost();
            if (host != null && isOwnSiteDomain(host)) {
                String regionalSiteUrl = regionRuleService.siteUrlFor(region);
                if (regionalSiteUrl != null && !regionalSiteUrl.isBlank()) {
                    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                    String query = uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "";
                    return regionalSiteUrl.replaceAll("/+$", "") + path + query;
                }
            }
        } catch (Exception ignored) {
            // Invalid URI format, return as-is
        }

        return trimmed;
    }

    private String extractUploadKey(String url) {
        String path = url;
        try {
            URI uri = URI.create(url);
            if (uri.getPath() != null) {
                path = uri.getPath();
            }
        } catch (Exception ignored) {
            // fallback to string searching
        }

        int idx = path.indexOf("/uploads/");
        if (idx >= 0) {
            String key = path.substring(idx + "/uploads/".length());
            int slash = key.indexOf('/');
            return slash >= 0 ? key.substring(0, slash) : key;
        }
        return null;
    }

    private boolean isOwnSiteDomain(String host) {
        if (host == null || regionRuleService == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        List<String> domains = regionRuleService.siteDomainsCached();
        if (domains != null) {
            for (String domain : domains) {
                if (domain != null) {
                    String normalizedDomain = domain.toLowerCase(Locale.ROOT);
                    if (normalized.equals(normalizedDomain) || normalized.endsWith("." + normalizedDomain)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
