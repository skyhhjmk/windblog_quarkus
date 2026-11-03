package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

/**
 * Resolves canonical URLs for the region of the current request. Each public
 * domain publishes its own canonical addresses: the Chinese site points at the
 * Chinese domain and the global site at the international one. The canonical
 * URL always uses that region's configured domain rule, so apex and www
 * requests collapse onto the same preferred host.
 */
@ApplicationScoped
public class CanonicalUrlService {

    @Inject
    RegionRuleService regionRuleService;

    @Inject
    SiteLinkUrlService siteLinkUrlService;

    @Inject
    PublicUrlService publicUrlService;

    @Inject
    LanguageSettingsService languageSettingsService;

    @Inject
    RegionContext regionContext;

    public String getBaseUrl() {
        BlogRegion region = currentRegion();
        String ruleUrl = regionRuleService == null ? "" : regionRuleService.siteUrlFor(region);
        if (ruleUrl != null && !ruleUrl.isBlank()) {
            return ruleUrl;
        }
        if (siteLinkUrlService != null && regionRuleService != null
                && regionRuleService.isMultiRegionEnabled()) {
            return siteLinkUrlService.urlFor(region);
        }
        return publicUrlService == null ? "" : publicUrlService.getBaseUrl();
    }

    /**
     * The default language of the region that owns the canonical base, so the
     * unprefixed path on each domain belongs to that region's own language.
     */
    public String getDefaultLanguage() {
        if (languageSettingsService == null) {
            return LanguageConstant.DEFAULT_LANG;
        }
        String language = languageSettingsService.defaultLanguage(currentRegion());
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        return normalized == null ? LanguageConstant.DEFAULT_LANG : normalized;
    }

    /**
     * Builds an absolute canonical URL for a page in the requested language,
     * using the canonical region's default language for the unprefixed form.
     */
    public String buildLocalizedPath(String path, String language) {
        String plain = LanguageHelper.stripLanguagePrefix(path == null ? "/" : path);
        String normalizedLanguage = LanguageHelper.normalizeToSupportedLang(language);
        if (normalizedLanguage == null) {
            normalizedLanguage = getDefaultLanguage();
        }
        return buildPath(LanguageHelper.localizedPath(plain, normalizedLanguage, getDefaultLanguage()));
    }

    public String buildPath(String path) {
        String normalizedPath = path == null || path.isBlank() ? "/" : path;
        return getBaseUrl() + (normalizedPath.startsWith("/") ? normalizedPath : "/" + normalizedPath);
    }

    private BlogRegion currentRegion() {
        if (regionContext == null) {
            return BlogRegion.GLOBAL;
        }
        try {
            BlogRegion region = regionContext.getCurrentRegion();
            return region == null ? BlogRegion.GLOBAL : region;
        } catch (ContextNotActiveException ignored) {
            return BlogRegion.GLOBAL;
        }
    }
}
