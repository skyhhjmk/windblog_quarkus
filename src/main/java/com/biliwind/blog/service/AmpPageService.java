package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Resolves the public, cache-safe representation used by the AMP controller and admin checker. */
@ApplicationScoped
public class AmpPageService {

    @Inject
    StructuredDataService structuredDataService;

    @Inject
    ConfigManager configManager;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    RegionContext regionContext;

    @Inject
    AmpContentRenderer ampContentRenderer;

    @Inject
    PageIdentityService pageIdentityService;

    @Inject
    SeoPageService seoPageService;

    @Inject
    RepostPolicyCatalog repostPolicyCatalog;

    public Optional<AmpPost> find(String slug, String languageCode) {
        String normalizedSlug = normalizeSlug(slug);
        String language = resolveLanguage(languageCode);
        String region = regionContext.getCurrentRegion().getCode();
        Optional<PublicCacheRefreshService.PublicPostSnapshot> snapshotResult =
                publicCacheRefreshService.findPublishedSnapshot(normalizedSlug, region);
        if (snapshotResult.isEmpty()) {
            return Optional.empty();
        }

        PublicCacheRefreshService.PublicPostSnapshot snapshot = snapshotResult.get();
        PageIdentityService.ArticleIdentity article = pageIdentityService.article(
                snapshot, snapshot.previewContent(), language, snapshot.visibility() != 0,
                seoPageService.policy(snapshot.postId()));
        PageIdentity identity = article.identity();
        String title = article.seoTitle();
        String summary = article.description();
        String contentLanguage = article.contentLanguage();
        String rawContent = resolveContent(snapshot.previewContent(), language);
        OffsetDateTime lastUpdated = snapshot.updatedAt() == null ? snapshot.publishedAt() : snapshot.updatedAt();

        if (snapshot.visibility() != 0 || snapshot.postPrice() > 0) {
            return Optional.of(AmpPost.unavailable(
                    normalizedSlug, language, title, summary, identity, lastUpdated));
        }

        AmpContentRenderer.RenderedContent rendered =
                ampContentRenderer.render(snapshot.renderType(), rawContent);
        RepostPolicyCatalog.Policy repostPolicy = repostPolicyCatalog.resolve(
                snapshot.repostPolicyCode(), snapshot.contentDeclarations());
        return Optional.of(new AmpPost(
                true,
                null,
                normalizedSlug,
                language,
                title,
                summary,
                rendered.html(),
                identity,
                lastUpdated,
                rendered.imageCount(),
                rendered.removedElementCount(),
                snapshot.renderType(),
                repostPolicy.nameFor(language),
                repostPolicy.summaryFor(language),
                repostPolicy.requiresApplication(),
                repostPolicy.conditionsFor(language),
                repostPolicy.licenseUrl(),
                identity.canonicalUrl(),
                structuredDataService.article(snapshot, title, summary,
                        identity.canonicalUrl(), contentLanguage, rendered.html())));
    }

    public boolean isEnabled() {
        return configManager.getBoolean("amp", "enabled", true);
    }

    private String resolveLanguage(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return LanguageConstant.DEFAULT_LANG;
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(languageCode);
        if (normalized == null || normalized.isBlank()) {
            return LanguageConstant.DEFAULT_LANG;
        }
        return normalized;
    }

    private String resolveContent(Map<String, String> content, String language) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return LanguageHelper.resolveLocalizedValue(content, language);
    }

    private String normalizeSlug(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.toLowerCase().endsWith(".html")) {
            normalized = normalized.substring(0, normalized.length() - 5);
        }
        return normalized;
    }

    public record AmpPost(
            boolean available,
            String unavailableReason,
            String slug,
            String language,
            String title,
            String summary,
            String html,
            PageIdentity identity,
            OffsetDateTime lastUpdated,
            int imageCount,
            int removedElementCount,
            PostRenderType renderType,
            String repostPolicyName,
            String repostPolicySummary,
            boolean repostPolicyRequiresApplication,
            List<String> repostPolicyConditions,
            String repostPolicyLicenseUrl,
            String repostOriginalUrl,
            String jsonLd) {

        public String canonicalUrl() {
            return identity.canonicalUrl();
        }

        public String ampUrl() {
            return identity.ampUrl();
        }

        public static AmpPost unavailable(
                String slug,
                String language,
                String title,
                String summary,
                PageIdentity identity,
                OffsetDateTime lastUpdated) {
            return new AmpPost(false, "受保护或付费文章不生成可缓存 AMP 页面", slug, language, title,
                    summary, "", identity, lastUpdated, 0, 0, null,
                    null, null, false, List.of(), null, null, null);
        }
    }
}
