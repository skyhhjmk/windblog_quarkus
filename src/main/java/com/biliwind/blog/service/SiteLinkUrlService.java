package com.biliwind.blog.service;

import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.net.URI;

/** Resolves the public site URL used when checking friendly-link backlinks. */
@ApplicationScoped
public class SiteLinkUrlService {

    private final SettingsReader settings;

    @Inject
    RegionContext regionContext;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    public SiteLinkUrlService(SettingsReader settings) {
        this.settings = settings;
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

    public String currentSiteName() {
        String title = settings.text(SettingKey.SITE_INFO, "title");
        return title == null || title.isBlank() ? "WindBlog" : title.trim();
    }

    public String urlFor(BlogRegion region) {
        String configuredUrl = normalizeConfiguredUrl(settings.siteUrl(region));
        return configuredUrl.isEmpty() ? publicUrlService.getBaseUrl() : configuredUrl;
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
