package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.SearchContentHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.SeoPostPolicy;
import com.biliwind.blog.service.PublicCacheRefreshService.PublicPostSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the identity of a public page. Article identities are built from the
 * already-loaded public snapshot, so the standard page, the AMP page and the
 * cache validator all consume one answer instead of re-querying the post.
 */
@ApplicationScoped
public class PageIdentityService {

    /**
     * Settings that feed the shared shell (base.html) and widgets. Their
     * fingerprint is part of every page validator, so a configuration change
     * cannot leave a cached page advertising the old title, theme or footer.
     */
    private static final List<String> RENDER_CONFIG_KEYS = List.of(
            "site_info", "site_footer", "appearance", "amp", "analytics_tracking",
            "feature_toggles", "seo", "social_links", "site_language", "google_adsense",
            "ai_comment_audit", "ai_operation_configs", "ai_translation",
            "security_network", "comment_security");

    @Inject
    CanonicalUrlService canonical;

    @Inject
    SeoPageService seoPages;

    @Inject
    SeoManagementService seoManagement;

    @Inject
    ConfigManager configManager;

    @Inject
    RegionContext regionContext;

    public ArticleIdentity article(PublicPostSnapshot snapshot,
                                   Map<String, String> contentByLanguage,
                                   String requestedLanguage,
                                   boolean visibilityNoindex,
                                   SeoPostPolicy policy) {
        Map<String, String> content = contentByLanguage == null ? Map.of() : contentByLanguage;
        String contentLanguage = LanguageHelper.resolveLocalizedLanguage(content, requestedLanguage);
        String displayTitle = LanguageHelper.resolveLocalizedValue(snapshot.title(), requestedLanguage);
        if (displayTitle == null || displayTitle.isBlank()) {
            displayTitle = snapshot.slug();
        }
        String seoTitle = firstNonBlank(
                LanguageHelper.lookupIgnoreCase(snapshot.seoTitle(), contentLanguage), displayTitle);
        String description = resolveDescription(snapshot, content, contentLanguage);
        String keywords = trimToNull(
                LanguageHelper.lookupIgnoreCase(snapshot.seoKeywords(), contentLanguage));

        boolean policyNoindex = policy != null && policy.noindex;
        boolean noindex = visibilityNoindex || policyNoindex;
        boolean nofollow = policy != null && policy.nofollow;
        String robots = seoManagement.robots(noindex, nofollow);

        String plainPath = "/post/" + snapshot.slug();
        String canonicalUrl = canonical.buildLocalizedPath(plainPath, contentLanguage);
        List<SeoPageService.Alternate> alternates = noindex
                ? List.of()
                : seoPages.alternatesForLanguages(plainPath,
                        availableLanguages(snapshot.title(), content));

        String ampUrl = null;
        if (!noindex && snapshot.postPrice() <= 0 && ampEnabled()) {
            String ampPath = LanguageHelper.localizedPath("/amp/post/" + snapshot.slug(),
                    contentLanguage, canonical.getDefaultLanguage());
            ampUrl = canonical.buildPath(ampPath);
        }
        return new ArticleIdentity(
                new PageIdentity(canonicalUrl, alternates, robots, ampUrl),
                displayTitle, contentLanguage, seoTitle, description, keywords);
    }

    public PageIdentity page(String path, String language) {
        String normalized = path == null || path.isBlank() ? "/" : path;
        int queryIndex = normalized.indexOf('?');
        String lookupPath = queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
        if (lookupPath.startsWith("/amp/post/")) {
            lookupPath = "/post/" + lookupPath.substring("/amp/post/".length());
        }
        String canonicalPath = queryIndex >= 0
                ? lookupPath + normalized.substring(queryIndex)
                : lookupPath;
        return new PageIdentity(
                canonical.buildLocalizedPath(canonicalPath, language),
                seoPages.alternates(lookupPath),
                seoManagement.robots(lookupPath),
                null);
    }

    /**
     * Identity for a page whose canonical is a fixed alias rather than the
     * language-prefixed request path (legal documents serve one copy for every
     * language).
     */
    public PageIdentity aliased(String canonicalUrl, String lookupPath, String language) {
        PageIdentity base = page(lookupPath, language);
        return new PageIdentity(canonicalUrl, base.alternates(), base.robots(), null);
    }

    public String environmentFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        BlogRegion region = region();
        for (String key : RENDER_CONFIG_KEYS) {
            fingerprint.append(key).append('=');
            try {
                JsonNode value = configManager.getForRegion(key, region);
                fingerprint.append(value == null ? "" : value.toString());
            } catch (RuntimeException ignored) {
                fingerprint.append("");
            }
            fingerprint.append('\n');
        }
        return fingerprint.toString();
    }

    private BlogRegion region() {
        if (regionContext == null) {
            return BlogRegion.GLOBAL;
        }
        try {
            BlogRegion region = regionContext.getCurrentRegion();
            return region == null ? BlogRegion.GLOBAL : region;
        } catch (RuntimeException ignored) {
            return BlogRegion.GLOBAL;
        }
    }

    private boolean ampEnabled() {
        return configManager.getBoolean("amp", "enabled", true);
    }

    private Set<String> availableLanguages(Map<String, String> title, Map<String, String> content) {
        Set<String> available = new LinkedHashSet<>();
        for (String language : LanguageConstant.SUPPORTED_LANG_ORDER) {
            if (LanguageHelper.hasLocalizedValue(title, language)
                    && LanguageHelper.hasLocalizedValue(content, language)) {
                available.add(language);
            }
        }
        return available;
    }

    private String resolveDescription(PublicPostSnapshot snapshot, Map<String, String> content,
                                      String language) {
        String seoDescription = LanguageHelper.lookupIgnoreCase(snapshot.seoDescription(), language);
        if (seoDescription != null && !seoDescription.isBlank()) {
            return limitDescription(seoDescription);
        }
        String summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);
        if (summary != null && !summary.isBlank()) {
            return limitDescription(summary);
        }
        String aiSummary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), language);
        if (aiSummary != null && !aiSummary.isBlank()) {
            return limitDescription(aiSummary);
        }
        return limitDescription(SearchContentHelper.toSearchableText(
                LanguageHelper.resolveLocalizedValue(content, language), snapshot.renderType()));
    }

    private String limitDescription(String description) {
        if (description == null) {
            return "";
        }
        String normalized = description.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ArticleIdentity(
            PageIdentity identity,
            String displayTitle,
            String contentLanguage,
            String seoTitle,
            String description,
            String keywords) {
    }
}
