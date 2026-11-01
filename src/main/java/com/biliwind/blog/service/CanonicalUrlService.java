package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
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

    @Inject
    LanguageSettingsService languageSettingsService;

    public String getBaseUrl() {
        if (!regionRuleService.isMultiRegionEnabled()) {
            return publicUrlService.getBaseUrl();
        }
        String globalUrl = regionRuleService.globalSiteUrl();
        return globalUrl.isBlank() ? siteLinkUrlService.urlFor(BlogRegion.GLOBAL) : globalUrl;
    }

    /**
     * The default language of the region that owns the canonical base. A
     * regional host (for example the Chinese domain) must not reuse its own
     * default language here, because the canonical URL points at the global
     * site where the unprefixed paths belong to the global default language.
     */
    public String getDefaultLanguage() {
        if (languageSettingsService == null) {
            return LanguageConstant.DEFAULT_LANG;
        }
        String language = languageSettingsService.defaultLanguage(BlogRegion.GLOBAL);
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        return normalized == null ? LanguageConstant.DEFAULT_LANG : normalized;
    }

    /**
     * Builds an absolute canonical URL for a page in the requested language,
     * using the global site's default language for the unprefixed form.
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
}
