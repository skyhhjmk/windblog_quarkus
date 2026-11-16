package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.controller.ToolsController;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.SeoPostPolicy;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Generates AI-friendly discovery endpoints ({@code /llms.txt}, {@code /llms-full.txt},
 * and {@code /post/{slug}.md}) following the llms.txt specification.
 */
@ApplicationScoped
public class LlmsService {

    private static final int LLMS_TXT_MAX_POSTS = 60;
    private static final int LLMS_FULL_MAX_POSTS = 30;
    private static final int FULL_BODY_CHAR_LIMIT = 4000;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    @Inject
    SettingsReader settings;

    @Inject
    FeedService feedService;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    PostAccessService postAccessService;

    @Inject
    SeoPageService seoPageService;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

    @Inject
    CacheService cacheService;

    public boolean isEnabled() {
        return settings.flag(SettingKey.SEO, "index_enabled")
                && settings.flag(SettingKey.SEO, "llms_txt_enabled");
    }

    public String getLlmsTxt() {
        ensureEnabled();
        String lang = currentLanguage();
        String region = currentRegionCode();
        String cacheKey = "seo:llms_txt:" + lang + ":" + region;
        return cacheService.getOrLoad(cacheKey, String.class, CACHE_TTL,
                        () -> buildLlmsTxt(lang, region))
                .orElseGet(() -> buildLlmsTxt(lang, region));
    }

    public String getLlmsFullTxt() {
        ensureEnabled();
        String lang = currentLanguage();
        String region = currentRegionCode();
        String cacheKey = "seo:llms_full_txt:" + lang + ":" + region;
        return cacheService.getOrLoad(cacheKey, String.class, CACHE_TTL,
                        () -> buildLlmsFullTxt(lang, region))
                .orElseGet(() -> buildLlmsFullTxt(lang, region));
    }

    public String getPostMarkdown(String slug) {
        ensureEnabled();
        if (slug == null || slug.isBlank()) {
            throw new NotFoundException();
        }
        String lang = currentLanguage();
        String region = currentRegionCode();

        Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null",
                slug, PostStatus.PUBLISHED).firstResult();
        if (post == null || post.publishedRevision == null || post.visibility != 0) {
            throw new NotFoundException();
        }
        if (postAccessService.getPostPrice(post) > 0) {
            throw new NotFoundException();
        }
        if (!RegionVisibility.isVisible(post.visibilityRegions, BlogRegion.fromCode(region))) {
            throw new NotFoundException();
        }
        SeoPostPolicy policy = seoPageService.policy(post.id);
        if (policy != null && policy.noindex) {
            throw new NotFoundException();
        }

        String title = LanguageHelper.resolveLocalizedValue(post.publishedRevision.title, lang);
        String markdown = LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
        if (title == null || title.isBlank() || markdown == null || markdown.isBlank()) {
            throw new NotFoundException();
        }

        String summary = LanguageHelper.resolveLocalizedValue(post.aiSummary, lang);
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        }
        String author = PostAuthorHelper.displayName(post);
        String canonicalUrl = canonicalUrlService.buildLocalizedPath(
                "/post/" + encodeSlug(post.slug), lang);

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yamlScalar(title)).append('\n');
        sb.append("url: ").append(canonicalUrl).append('\n');
        if (post.publishedAt != null) {
            sb.append("published: ").append(post.publishedAt).append('\n');
        }
        if (post.updatedAt != null) {
            sb.append("updated: ").append(post.updatedAt).append('\n');
        }
        if (author != null && !author.isBlank()) {
            sb.append("author: ").append(yamlScalar(author)).append('\n');
        }
        if (summary != null && !summary.isBlank()) {
            sb.append("summary: ").append(yamlScalar(summary.strip())).append('\n');
        }
        sb.append("---\n\n");
        sb.append("# ").append(title.strip()).append("\n\n");
        sb.append(markdown.strip()).append('\n');
        return sb.toString();
    }

    private String buildLlmsTxt(String lang, String region) {
        String baseUrl = feedService.getBaseUrl();
        String siteTitle = ConfigTemplateData.siteTitle();
        String siteDesc = ConfigTemplateData.siteDescription();

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(siteTitle).append("\n\n");
        if (siteDesc != null && !siteDesc.isBlank()) {
            sb.append("> ").append(siteDesc.replace('\n', ' ').strip()).append("\n\n");
        }
        sb.append("Canonical Base URL: ").append(baseUrl).append("\n\n");

        sb.append("## Core Navigation\n");
        sb.append("- [Home](").append(canonicalUrlService.buildLocalizedPath("/", lang)).append("): Latest articles and updates\n");
        sb.append("- [Online Tools](").append(canonicalUrlService.buildLocalizedPath("/tools", lang)).append("): Privacy-first browser developer utilities\n");
        sb.append("- [Search](").append(canonicalUrlService.buildLocalizedPath("/search", lang)).append("): Full-text site search\n");
        sb.append("- [RSS Feed](").append(baseUrl).append("/rss.xml): XML feed of published articles\n");
        sb.append("- [Sitemap](").append(baseUrl).append("/sitemap.xml): XML sitemap index\n");
        sb.append("- [Full Digest](").append(baseUrl).append("/llms-full.txt): Extended markdown digest of recent articles\n\n");

        sb.append("## Developer Tools\n");
        for (ToolsController.ToolItem tool : ToolsController.allTools()) {
            String toolUrl = canonicalUrlService.buildLocalizedPath(tool.url(), lang);
            sb.append("- [").append(tool.name()).append("](").append(toolUrl).append("): ")
                    .append(tool.description()).append('\n');
        }
        sb.append('\n');

        sb.append("## Recent Articles\n");
        List<PublicCacheRefreshService.PublicPostListSnapshot> posts =
                publicCacheRefreshService.findPublishedFeed(LLMS_TXT_MAX_POSTS, lang, region);
        for (PublicCacheRefreshService.PublicPostListSnapshot post : posts) {
            String title = LanguageHelper.resolveLocalizedValue(post.title(), lang);
            if (title == null || title.isBlank()) {
                continue;
            }
            String summary = LanguageHelper.resolveLocalizedValue(post.aiSummary(), lang);
            if (summary == null || summary.isBlank()) {
                summary = LanguageHelper.resolveLocalizedValue(post.summary(), lang);
            }
            String postPath = "/post/" + encodeSlug(post.slug());
            String postUrl = canonicalUrlService.buildLocalizedPath(postPath, lang);
            String mdUrl = canonicalUrlService.buildLocalizedPath(postPath + ".md", lang);
            sb.append("- [").append(title.strip()).append("](").append(postUrl).append(")");
            sb.append(" ([Markdown](").append(mdUrl).append("))");
            if (summary != null && !summary.isBlank()) {
                sb.append(": ").append(summary.replace('\n', ' ').strip());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String buildLlmsFullTxt(String lang, String region) {
        StringBuilder sb = new StringBuilder(buildLlmsTxt(lang, region));
        sb.append("\n---\n\n## Article Digests\n\n");

        List<PublicCacheRefreshService.PublicPostListSnapshot> posts =
                publicCacheRefreshService.findPublishedFeed(LLMS_FULL_MAX_POSTS, lang, region);
        for (PublicCacheRefreshService.PublicPostListSnapshot item : posts) {
            PublicCacheRefreshService.PublicPostSnapshot detail =
                    publicCacheRefreshService.findPublishedSnapshot(item.slug(), region).orElse(null);
            if (detail == null || detail.visibility() != 0 || detail.postPrice() > 0) {
                continue;
            }
            SeoPostPolicy policy = seoPageService.policy(detail.postId());
            if (policy != null && policy.noindex) {
                continue;
            }
            String title = LanguageHelper.resolveLocalizedValue(detail.title(), lang);
            if (title == null || title.isBlank()) {
                continue;
            }
            String postUrl = canonicalUrlService.buildLocalizedPath("/post/" + encodeSlug(detail.slug()), lang);
            String mdUrl = canonicalUrlService.buildLocalizedPath("/post/" + encodeSlug(detail.slug()) + ".md", lang);
            sb.append("### ").append(title.strip()).append('\n');
            sb.append("URL: ").append(postUrl).append('\n');
            sb.append("Markdown: ").append(mdUrl).append('\n');
            if (detail.publishedAt() != null) {
                sb.append("Published: ").append(detail.publishedAt()).append('\n');
            }
            String summary = LanguageHelper.resolveLocalizedValue(detail.aiSummary(), lang);
            if (summary == null || summary.isBlank()) {
                summary = LanguageHelper.resolveLocalizedValue(detail.summary(), lang);
            }
            if (summary != null && !summary.isBlank()) {
                sb.append("\n").append(summary.strip()).append('\n');
            }
            String preview = LanguageHelper.resolveLocalizedValue(detail.previewContent(), lang);
            if (preview != null && !preview.isBlank()) {
                String plain = preview.strip();
                if (plain.length() > FULL_BODY_CHAR_LIMIT) {
                    plain = plain.substring(0, FULL_BODY_CHAR_LIMIT) + "\n...[truncated; see Markdown URL]";
                }
                sb.append("\n").append(plain).append('\n');
            }
            sb.append("\n---\n\n");
        }
        return sb.toString();
    }

    private void ensureEnabled() {
        if (!isEnabled()) {
            throw new NotFoundException();
        }
    }

    private String currentLanguage() {
        try {
            String lang = languageContext != null ? languageContext.getLang() : null;
            return (lang == null || lang.isBlank()) ? "zh-CN" : lang;
        } catch (ContextNotActiveException ignored) {
            return "zh-CN";
        }
    }

    private String currentRegionCode() {
        if (regionContext == null) {
            return BlogRegion.GLOBAL.getCode();
        }
        try {
            BlogRegion region = regionContext.getCurrentRegion();
            return region == null ? BlogRegion.GLOBAL.getCode() : region.getCode();
        } catch (ContextNotActiveException ignored) {
            return BlogRegion.GLOBAL.getCode();
        }
    }

    private static String encodeSlug(String slug) {
        return URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String yamlScalar(String value) {
        String cleaned = value.replace("\r", " ").replace("\n", " ").replace("\"", "\\\"");
        return "\"" + cleaned + "\"";
    }
}
