package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.service.link.LinkMonitorPolicy;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class FeedService {

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

    @Inject
    CanonicalUrlService canonicalUrlService;

    private static final DateTimeFormatter RFC_822_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter ISO_8601_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_PUBLIC_FEED_POSTS = 500;
    @Inject SeoPageService seoPageService;
    @Inject PostAccessService postAccessService;
    @Inject PublicContentSanitizer sanitizer;
    @Inject ConfigManager config;

    public List<FeedPostView> getPublishedPosts() {
        String language = languageContext.getLang();
        String region = regionContext.getCurrentRegion().getCode();
        List<PublicCacheRefreshService.PublicPostListSnapshot> snapshots =
                publicCacheRefreshService.findPublishedFeed(MAX_PUBLIC_FEED_POSTS, language, region);
        List<FeedPostView> result = new ArrayList<>();
        for (PublicCacheRefreshService.PublicPostListSnapshot snapshot : snapshots) {
            result.add(toView(snapshot, language));
        }
        return result;
    }

    private static final int SITEMAP_PAGE_SIZE = 1000;

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

    private String sitemapPostQuery() {
        return "status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null "
                + "and id not in (select policy.postId from SeoPostPolicy policy where policy.noindex = true) "
                + "and (visibilityRegions is null or cast(visibilityRegions as String) like ?2 or cast(visibilityRegions as String) like '%\"global\"%')";
    }

    public List<Integer> getPostSitemapPages() {
        String regionPattern = "%\"" + currentRegionCode() + "\"%";
        long count = com.biliwind.blog.model.Post.count(sitemapPostQuery(), com.biliwind.blog.model.PostStatus.PUBLISHED, regionPattern);
        return java.util.stream.IntStream.rangeClosed(1, Math.max(1, (int) ((count + SITEMAP_PAGE_SIZE - 1) / SITEMAP_PAGE_SIZE)))
                .boxed().toList();
    }

    public List<PostSitemapUrlView> getPostSitemapUrls(int page) {
        if (page < 1 || page > getPostSitemapPages().size()) throw new jakarta.ws.rs.NotFoundException();
        String regionPattern = "%\"" + currentRegionCode() + "\"%";
        List<com.biliwind.blog.model.Post> posts = com.biliwind.blog.model.Post.find(
                sitemapPostQuery() + " order by id", com.biliwind.blog.model.PostStatus.PUBLISHED, regionPattern)
                .page(io.quarkus.panache.common.Page.of(page - 1, SITEMAP_PAGE_SIZE)).list();
        List<PostSitemapUrlView> urls = new ArrayList<>();
        if (!config.getBoolean("seo", "index_enabled", true)) return urls;
        for (var post : posts) {
            String plainPath = "/post/" + encodeSlug(post.slug);
            List<String> languages = new ArrayList<>();
            for (String lang : com.biliwind.blog.common.constant.LanguageConstant.SUPPORTED_LANG_ORDER) {
                if (hasLanguage(post.publishedRevision.title, lang)
                        && hasLanguage(post.publishedRevision.contentMarkdown, lang)) {
                    languages.add(lang);
                }
            }
            if (languages.isEmpty()) {
                continue;
            }
            // Compute the alternates from the revision already loaded above;
            // resolving them per URL would run one extra query per entry.
            List<SeoPageService.Alternate> alternates =
                    seoPageService.alternatesForLanguages(plainPath, languages);
            for (String lang : languages) {
                String loc = canonicalUrlService.buildLocalizedPath(plainPath, lang);
                List<String> images = List.of();
                if (postAccessService.getPostPrice(post) == 0) {
                    String content = LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
                    String html = post.renderType == com.biliwind.blog.model.PostRenderType.MARKDOWN
                            || post.renderType == com.biliwind.blog.model.PostRenderType.FLUTTER_MARKDOWN_PLUS
                            ? com.biliwind.blog.common.helper.MarkdownHelper.toHtml(content) : content;
                    images = StructuredDataService.imageUrls(sanitizer.sanitize(html), loc);
                }
                urls.add(new PostSitemapUrlView(loc, formatLastModified(post.updatedAt, post.createdAt), images,
                        alternates));
            }
        }
        return urls;
    }

    private boolean hasLanguage(java.util.Map<String, String> values, String language) {
        return LanguageHelper.hasLocalizedValue(values, language);
    }

    private String encodeSlug(String slug) {
        return java.net.URLEncoder.encode(slug, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public String getBaseUrl() {
        return canonicalUrlService.getBaseUrl();
    }

    public String getCurrentRfc822Date() {
        return OffsetDateTime.now().format(RFC_822_FORMATTER);
    }

    public String getCurrentLanguage() {
        return languageContext.getLang();
    }

    public String getHomeUrl() {
        return canonicalUrlService.buildLocalizedPath("/", languageContext.getLang());
    }

    private FeedPostView toView(PublicCacheRefreshService.PublicPostListSnapshot snapshot, String language) {
        String title = LanguageHelper.resolveLocalizedValue(snapshot.title(), language);
        if (title == null || title.isBlank()) title = snapshot.slug();

        String summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), language);
        }
        if (summary == null) {
            summary = "";
        }

        // The feed falls back to another language's title when a translation is
        // missing; link to the URL that actually renders that content.
        String contentLanguage = LanguageHelper.resolveLocalizedLanguage(snapshot.title(), language);
        String url = canonicalUrlService.buildLocalizedPath(
                "/post/" + encodeSlug(snapshot.slug()), contentLanguage);
        return new FeedPostView(
                snapshot.slug(),
                url,
                title,
                summary,
                snapshot.publishedAt() != null ? snapshot.publishedAt().format(RFC_822_FORMATTER) : "",
                formatLastModified(snapshot.updatedAt(), snapshot.createdAt()),
                snapshot.authorName()
        );
    }

    public List<Integer> getCategorySitemapPages() { return sitemapPages(Category.count("enabled = true")); }
    public List<Integer> getTagSitemapPages() { return sitemapPages(Tag.count()); }
    private List<Integer> sitemapPages(long count) {
        return java.util.stream.IntStream.rangeClosed(1, Math.max(1, (int) ((count + SITEMAP_PAGE_SIZE - 1) / SITEMAP_PAGE_SIZE))).boxed().toList();
    }

    public List<SitemapUrlView> getCategorySitemapUrls() { return getCategorySitemapUrls(1); }
    public List<SitemapUrlView> getCategorySitemapUrls(int page) {
        if (!config.getBoolean("seo", "index_enabled", true)) return List.of();
        if (page < 1 || page > getCategorySitemapPages().size()) throw new jakarta.ws.rs.NotFoundException();
        List<Category> categories = Category.find("enabled = true order by path, id")
                .page(io.quarkus.panache.common.Page.of(page - 1, SITEMAP_PAGE_SIZE)).list();
        List<SitemapUrlView> result = new ArrayList<>();
        for (Category category : categories) {
            addLocalizedEntries(result, "/category/" + encodeSlug(category.slug),
                    formatLastModified(category.updatedAt, category.createdAt));
        }
        return result;
    }

    public List<SitemapUrlView> getTagSitemapUrls() { return getTagSitemapUrls(1); }
    public List<SitemapUrlView> getTagSitemapUrls(int page) {
        if (!config.getBoolean("seo", "index_enabled", true)) return List.of();
        if (page < 1 || page > getTagSitemapPages().size()) throw new jakarta.ws.rs.NotFoundException();
        List<Tag> tags = Tag.find("order by id")
                .page(io.quarkus.panache.common.Page.of(page - 1, SITEMAP_PAGE_SIZE)).list();
        List<SitemapUrlView> result = new ArrayList<>();
        for (Tag tag : tags) {
            addLocalizedEntries(result, "/tag/" + encodeSlug(tag.slug),
                    formatLastModified(tag.updatedAt, tag.createdAt));
        }
        return result;
    }

    public List<SitemapUrlView> getPageSitemapUrls() {
        if (!config.getBoolean("seo", "index_enabled", true)) return List.of();
        List<SitemapUrlView> result = new ArrayList<>();
        for (String path : pagePaths()) {
            addLocalizedEntries(result, path, "");
        }
        return result;
    }

    public List<Integer> getLinkSitemapPages() {
        return sitemapPages(Link.count("status = 1"));
    }

    public List<SitemapUrlView> getLinkSitemapUrls() {
        return getLinkSitemapUrls(1);
    }

    public List<SitemapUrlView> getLinkSitemapUrls(int page) {
        if (!config.getBoolean("seo", "index_enabled", true)) return List.of();
        if (page < 1 || page > getLinkSitemapPages().size()) {
            throw new jakarta.ws.rs.NotFoundException();
        }
        List<Link> links = Link.find("status = 1 order by id")
                .page(io.quarkus.panache.common.Page.of(page - 1, SITEMAP_PAGE_SIZE)).list();
        List<SitemapUrlView> result = new ArrayList<>();
        for (Link link : links) {
            if (!LinkMonitorPolicy.isVisibleOnPublicPage(link)
                    || !LinkMonitorPolicy.isVisibleInRegion(link, BlogRegion.GLOBAL)) {
                continue;
            }
            addLocalizedEntries(result, "/link/" + link.id,
                    formatLastModified(link.updatedAt, link.createdAt));
        }
        return result;
    }

    /**
     * Emits one URL per advertised language (skipping x-default) so every
     * translated page is directly discoverable from the sitemap.
     */
    private void addLocalizedEntries(List<SitemapUrlView> result, String path, String lastmod) {
        List<SeoPageService.Alternate> alternates = seoPageService.alternates(path);
        if (alternates.isEmpty()) {
            result.add(new SitemapUrlView(getBaseUrl() + path, lastmod, List.of()));
            return;
        }
        for (SeoPageService.Alternate alternate : alternates) {
            if ("x-default".equals(alternate.language())) {
                continue;
            }
            result.add(new SitemapUrlView(alternate.url(), lastmod, alternates));
        }
    }

    private List<String> pagePaths() {
        return List.of("/", "/tools", "/tools/subnet", "/tools/timestamp", "/tools/log-formatter",
                "/tools/base-encoder", "/tools/cron", "/tools/regex", "/tools/hash", "/tools/json-diff",
                "/category", "/tag", "/link", "/terms", "/privacy");
    }

    private String formatLastModified(OffsetDateTime updatedAt, OffsetDateTime createdAt) {
        if (updatedAt != null) {
            return updatedAt.format(ISO_8601_FORMATTER);
        }
        if (createdAt != null) {
            return createdAt.format(ISO_8601_FORMATTER);
        }
        return "";
    }

    @TemplateData
    public record FeedPostView(
            String slug,
            String url,
            String title,
            String summary,
            String pubDate,
            String lastModified,
            String author
    ) {}

    @TemplateData
    public record PostSitemapUrlView(String loc, String lastmod, List<String> images, List<SeoPageService.Alternate> alternates) {}

    @TemplateData
    public record SitemapUrlView(
            String loc,
            String lastmod,
            List<SeoPageService.Alternate> alternates
    ) {}
}
