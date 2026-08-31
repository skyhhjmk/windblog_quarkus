package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/** Resolves the public, cache-safe representation used by the AMP controller and admin checker. */
@ApplicationScoped
public class AmpPageService {

    @ConfigProperty(name = "windblog.amp.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    RegionContext regionContext;

    @Inject
    AmpContentRenderer ampContentRenderer;

    @Inject
    PublicUrlService publicUrlService;

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
        String title = LanguageHelper.resolveLocalizedValue(snapshot.title(), language);
        if (title == null || title.isBlank()) {
            title = normalizedSlug;
        }
        String summary = resolveSummary(snapshot, language);
        String rawContent = resolveContent(snapshot.previewContent(), language);

        if (snapshot.visibility() != 0 || snapshot.postPrice() > 0) {
            return Optional.of(AmpPost.unavailable(
                    normalizedSlug,
                    language,
                    title,
                    summary,
                    buildCanonicalUrl(normalizedSlug, language),
                    buildAmpUrl(normalizedSlug, language),
                    snapshot.updatedAt() == null ? snapshot.publishedAt() : snapshot.updatedAt()));
        }

        AmpContentRenderer.RenderedContent rendered =
                ampContentRenderer.render(snapshot.renderType(), rawContent);
        RepostPolicyCatalog.Policy repostPolicy = repostPolicyCatalog.resolve(
                snapshot.repostPolicyCode(), snapshot.contentDeclarations());
        String canonicalUrl = buildCanonicalUrl(normalizedSlug, language);
        return Optional.of(new AmpPost(
                true,
                null,
                normalizedSlug,
                language,
                title,
                summary,
                rendered.html(),
                canonicalUrl,
                buildAmpUrl(normalizedSlug, language),
                snapshot.updatedAt() == null ? snapshot.publishedAt() : snapshot.updatedAt(),
                rendered.imageCount(),
                rendered.removedElementCount(),
                snapshot.renderType(),
                repostPolicy.name(),
                repostPolicy.requiresApplication(),
                repostPolicy.conditions(),
                repostPolicy.licenseUrl(),
                canonicalUrl));
    }

    public boolean isEnabled() {
        return enabled;
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

    private String resolveSummary(PublicCacheRefreshService.PublicPostSnapshot snapshot, String language) {
        String summary = snapshot.seoDescription();
        if (summary != null) {
            summary = summary.trim();
        }
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);
        }
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), language);
        }
        if (summary == null) {
            return "";
        }
        String normalized = summary.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160);
    }

    private String buildCanonicalUrl(String slug, String language) {
        if (LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(language)) {
            return publicUrlService.buildPath("/post/" + slug);
        }
        return publicUrlService.buildPath("/" + language + "/post/" + slug);
    }

    private String buildAmpUrl(String slug, String language) {
        if (LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(language)) {
            return publicUrlService.buildPath("/amp/post/" + slug);
        }
        return publicUrlService.buildPath("/" + language + "/amp/post/" + slug);
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
            String canonicalUrl,
            String ampUrl,
            OffsetDateTime lastUpdated,
            int imageCount,
            int removedElementCount,
            PostRenderType renderType,
            String repostPolicyName,
            boolean repostPolicyRequiresApplication,
            List<String> repostPolicyConditions,
            String repostPolicyLicenseUrl,
            String repostOriginalUrl) {

        public static AmpPost unavailable(
                String slug,
                String language,
                String title,
                String summary,
                String canonicalUrl,
                String ampUrl,
                OffsetDateTime lastUpdated) {
            return new AmpPost(false, "受保护或付费文章不生成可缓存 AMP 页面", slug, language, title,
                    summary, "", canonicalUrl, ampUrl, lastUpdated, 0, 0, null,
                    null, false, List.of(), null, null);
        }
    }
}
