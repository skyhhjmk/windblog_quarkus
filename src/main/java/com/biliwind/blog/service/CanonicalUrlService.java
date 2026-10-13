package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Resolves every regional page to the corresponding global canonical URL. */
@ApplicationScoped
public class CanonicalUrlService {

    @Inject
    RegionRuleService regionRuleService;

    @Inject
    SiteLinkUrlService siteLinkUrlService;

    @Inject
    PublicUrlService publicUrlService;

    public String getBaseUrl() {
        if (!regionRuleService.isMultiRegionEnabled()) {
            return publicUrlService.getBaseUrl();
        }
        String globalUrl = regionRuleService.globalSiteUrl();
        return globalUrl.isBlank() ? siteLinkUrlService.urlFor(BlogRegion.GLOBAL) : globalUrl;
    }

    public String buildPath(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        return getBaseUrl() + (normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath);
    }
}
