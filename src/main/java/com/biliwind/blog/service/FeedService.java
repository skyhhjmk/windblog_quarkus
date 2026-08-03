package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

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

    @ConfigProperty(name = "blog.url")
    String baseUrl;

    private static final DateTimeFormatter RFC_822_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter ISO_8601_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int MAX_PUBLIC_FEED_POSTS = 500;
    private static final int MAX_PUBLIC_TAXONOMY_ITEMS = 1000;

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

    public String getBaseUrl() {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
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

    public List<SitemapUrlView> getCategorySitemapUrls() {
        List<Category> categories = Category.find("order by path")
                .page(io.quarkus.panache.common.Page.ofSize(MAX_PUBLIC_TAXONOMY_ITEMS)).list();
        List<SitemapUrlView> result = new ArrayList<>();
        for (Category category : categories) {
            result.add(new SitemapUrlView(
                    getBaseUrl() + "/category/" + category.slug,
                    formatLastModified(category.updatedAt, category.createdAt)));
        }
        return result;
    }

    public List<SitemapUrlView> getTagSitemapUrls() {
        List<Tag> tags = Tag.find("order by id")
                .page(io.quarkus.panache.common.Page.ofSize(MAX_PUBLIC_TAXONOMY_ITEMS)).list();
        List<SitemapUrlView> result = new ArrayList<>();
        for (Tag tag : tags) {
            result.add(new SitemapUrlView(
                    getBaseUrl() + "/tag/" + tag.slug,
                    formatLastModified(tag.updatedAt, tag.createdAt)));
        }
        return result;
    }

    public List<SitemapUrlView> getPageSitemapUrls() {
        String normalizedBaseUrl = getBaseUrl();
        return List.of(
                new SitemapUrlView(normalizedBaseUrl + "/", ""),
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

    public record FeedPostView(
            String slug,
            String title,
            String summary,
            String pubDate,
            String lastModified,
            String author
    ) {}

    public record SitemapUrlView(
            String loc,
            String lastmod
    ) {}
}
