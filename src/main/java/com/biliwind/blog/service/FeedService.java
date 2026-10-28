package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Tag;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
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

    private String sitemapPostQuery() {
        return "status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null "
                + "and id not in (select policy.postId from SeoPostPolicy policy where policy.noindex = true) "
                + "and (visibilityRegions is null or cast(visibilityRegions as String) like '%\"global\"%')";
    }

    public List<Integer> getPostSitemapPages() {
        long count = com.biliwind.blog.model.Post.count(sitemapPostQuery(), com.biliwind.blog.model.PostStatus.PUBLISHED);
        return java.util.stream.IntStream.rangeClosed(1, Math.max(1, (int) ((count + SITEMAP_PAGE_SIZE - 1) / SITEMAP_PAGE_SIZE)))
                .boxed().toList();
    }

    public List<PostSitemapUrlView> getPostSitemapUrls(int page) {
        if (page < 1 || page > getPostSitemapPages().size()) throw new jakarta.ws.rs.NotFoundException();
        List<com.biliwind.blog.model.Post> posts = com.biliwind.blog.model.Post.find(
                sitemapPostQuery() + " order by id", com.biliwind.blog.model.PostStatus.PUBLISHED)
                .page(io.quarkus.panache.common.Page.of(page - 1, SITEMAP_PAGE_SIZE)).list();
        List<PostSitemapUrlView> urls = new ArrayList<>();
        if (!config.getBoolean("seo", "index_enabled", true)) return urls;
        for (var post : posts) {
            for (String lang : com.biliwind.blog.common.constant.LanguageConstant.SUPPORT_LANGS.stream().sorted().toList()) {
                if (!hasLanguage(post.publishedRevision.title, lang) || !hasLanguage(post.publishedRevision.contentMarkdown, lang)) continue;
                String prefix = com.biliwind.blog.common.constant.LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(lang) ? "" : "/" + lang;
                String loc = getBaseUrl() + prefix + "/post/" + encodeSlug(post.slug);
                List<String> images = List.of();
                if (postAccessService.getPostPrice(post) == 0) {
                    String content = LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
                    String html = post.renderType == com.biliwind.blog.model.PostRenderType.MARKDOWN
                            || post.renderType == com.biliwind.blog.model.PostRenderType.FLUTTER_MARKDOWN_PLUS
                            ? com.biliwind.blog.common.helper.MarkdownHelper.toHtml(content) : content;
                    images = StructuredDataService.imageUrls(sanitizer.sanitize(html), loc);
                }
                urls.add(new PostSitemapUrlView(loc, formatLastModified(post.updatedAt, post.createdAt), images,
                        seoPageService.alternates(prefix + "/post/" + encodeSlug(post.slug))));
            }
        }
        return urls;
    }

    private boolean hasLanguage(java.util.Map<String, String> values, String language) {
        return values != null && values.entrySet().stream().anyMatch(e -> language.equalsIgnoreCase(e.getKey())
                && e.getValue() != null && !e.getValue().isBlank());
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

    private FeedPostView toView(PublicCacheRefreshService.PublicPostListSnapshot snapshot, String language) {
        String title = LanguageHelper.resolveLocalizedValue(snapshot.title(), language);
        if (title == null || title.isBlank()) title = snapshot.slug();

        String summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);
        if (summary == null || summary.isBlank()) {
            summary = LanguageHelper.resolveLocalizedValue(snapshot.aiSummary(), language);
        }

        return new FeedPostView(
                snapshot.slug(),
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
            result.add(new SitemapUrlView(
                    getBaseUrl() + "/category/" + encodeSlug(category.slug),
                    formatLastModified(category.updatedAt, category.createdAt)));
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
            result.add(new SitemapUrlView(
                    getBaseUrl() + "/tag/" + encodeSlug(tag.slug),
                    formatLastModified(tag.updatedAt, tag.createdAt)));
        }
        return result;
    }

    public List<SitemapUrlView> getPageSitemapUrls() {
        if (!config.getBoolean("seo", "index_enabled", true)) return List.of();
        String normalizedBaseUrl = getBaseUrl();
        return List.of(
                new SitemapUrlView(normalizedBaseUrl + "/", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tools", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tools/subnet", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tools/timestamp", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tools/log-formatter", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tools/base-encoder", ""),
                new SitemapUrlView(normalizedBaseUrl + "/category", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tag", ""),
                new SitemapUrlView(normalizedBaseUrl + "/link", "")
        );
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
            String lastmod
    ) {}
}
