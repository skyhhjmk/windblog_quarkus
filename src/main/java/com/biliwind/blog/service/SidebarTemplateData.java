package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.Tag;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class SidebarTemplateData {

    private static final long LOCAL_CACHE_TTL_MILLIS = 10000L;
    private static final int MAX_PUBLIC_CATEGORIES = 200;
    private static final int MAX_PUBLIC_TAGS = 500;
    private static final ConcurrentHashMap<String, LocalCacheEntry> LOCAL_CACHE = new ConcurrentHashMap<>();

    private static LanguageContext getLanguageContext() {
        return jakarta.enterprise.inject.spi.CDI.current().select(LanguageContext.class).get();
    }

    private static com.biliwind.blog.context.RegionContext getRegionContext() {
        return jakarta.enterprise.inject.spi.CDI.current().select(com.biliwind.blog.context.RegionContext.class).get();
    }

    private static CacheService getCacheService() {
        return jakarta.enterprise.inject.spi.CDI.current().select(CacheService.class).get();
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<PostView> recentPosts(int limit) {
        String lang = getLanguageContext().getLang();
        String region = getRegionContext().getCurrentRegion().getCode();
        String cacheKey = CacheService.Keys.recentPosts(lang, region, limit);
        Object localValue = getLocalValue(cacheKey);
        if (localValue instanceof List) {
            return (List<PostView>) localValue;
        }

        java.util.Optional<List<PostView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<PostView>>() {
        });
        if (cached.isPresent()) {
            List<PostView> cachedPosts = cached.get();
            setLocalValue(cacheKey, cachedPosts);
            return cachedPosts;
        }

        List<Post> posts = Post.find("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2)",
                        Sort.descending("publishedAt").and("createdAt").descending(),
                        com.biliwind.blog.model.PostStatus.PUBLISHED,
                        "%\"" + region + "\"%")
                .page(0, limit)
                .list();

        List<PostView> result = new ArrayList<>();
        for (Post p : posts) {
            String title = LanguageHelper.resolveLocalizedValue(p.title, lang);
            String safeTitle = resolvePostTitle(title, p.slug, p.id);
            String titleInitial = resolveTitleInitial(safeTitle);
            result.add(new PostView(safeTitle, titleInitial, p.slug, p.publishedAt, p.createdAt));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        setLocalValue(cacheKey, result);
        return result;
    }

    private static String resolvePostTitle(String localizedTitle, String postSlug, Long postId) {
        if (localizedTitle != null) {
            if (!localizedTitle.isBlank()) {
                return localizedTitle;
            }
        }

        if (postSlug != null) {
            if (!postSlug.isBlank()) {
                return postSlug;
            }
        }

        if (postId != null) {
            return "文章 " + postId;
        }

        return "未命名文章";
    }

    private static String resolveTitleInitial(String title) {
        if (title == null) {
            return "?";
        }

        if (title.isBlank()) {
            return "?";
        }

        return title.substring(0, 1);
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<CategoryView> categories() {
        String lang = getLanguageContext().getLang();
        String cacheKey = CacheService.Keys.categories(lang);
        Object localValue = getLocalValue(cacheKey);
        if (localValue instanceof List) {
            return (List<CategoryView>) localValue;
        }

        java.util.Optional<List<CategoryView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<CategoryView>>() {
        });
        if (cached.isPresent()) {
            List<CategoryView> cachedCategories = cached.get();
            setLocalValue(cacheKey, cachedCategories);
            return cachedCategories;
        }

        List<Category> categories = Category.find("order by path")
                .page(0, MAX_PUBLIC_CATEGORIES)
                .list();

        List<CategoryView> result = new ArrayList<>();
        for (Category c : categories) {
            String name = LanguageHelper.resolveLocalizedValue(c.name, lang);
            result.add(new CategoryView(name, c.slug, c.postCount));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        setLocalValue(cacheKey, result);
        return result;
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<TagView> tags() {
        String lang = getLanguageContext().getLang();
        String cacheKey = CacheService.Keys.tags(lang);
        Object localValue = getLocalValue(cacheKey);
        if (localValue instanceof List) {
            return (List<TagView>) localValue;
        }

        java.util.Optional<List<TagView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<TagView>>() {
        });
        if (cached.isPresent()) {
            List<TagView> cachedTags = cached.get();
            setLocalValue(cacheKey, cachedTags);
            return cachedTags;
        }

        List<Tag> tags = Tag.find("order by id")
                .page(0, MAX_PUBLIC_TAGS)
                .list();

        List<TagView> result = new ArrayList<>();
        for (Tag t : tags) {
            String name = LanguageHelper.resolveLocalizedValue(t.name, lang);
            result.add(new TagView(name, t.slug));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        setLocalValue(cacheKey, result);
        return result;
    }

    @TemplateExtension(namespace = "sidebar")
    public static StatsView stats() {
        String cacheKey = CacheService.Keys.SIDEBAR_STATS;
        Object localValue = getLocalValue(cacheKey);
        if (localValue instanceof StatsView) {
            return (StatsView) localValue;
        }

        java.util.Optional<StatsView> cached = getCacheService().get(cacheKey, StatsView.class);
        if (cached.isPresent()) {
            StatsView cachedStats = cached.get();
            setLocalValue(cacheKey, cachedStats);
            return cachedStats;
        }

        long postCount = Post.count("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                com.biliwind.blog.model.PostStatus.PUBLISHED);
        long categoryCount = Category.count();
        long tagCount = Tag.count();
        long commentCount = Comment.count();

        StatsView result = new StatsView(postCount, categoryCount, tagCount, commentCount);
        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        setLocalValue(cacheKey, result);
        return result;
    }

    private static Object getLocalValue(String cacheKey) {
        LocalCacheEntry localCacheEntry = LOCAL_CACHE.get(cacheKey);
        if (localCacheEntry == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (localCacheEntry.expiresAtMillis <= now) {
            LOCAL_CACHE.remove(cacheKey);
            return null;
        }

        return localCacheEntry.value;
    }

    private static void setLocalValue(String cacheKey, Object value) {
        long expiresAtMillis = System.currentTimeMillis() + LOCAL_CACHE_TTL_MILLIS;
        LocalCacheEntry localCacheEntry = new LocalCacheEntry(value, expiresAtMillis);
        LOCAL_CACHE.put(cacheKey, localCacheEntry);
    }

    @TemplateData
    public record PostView(String title, String titleInitial, String slug, java.time.OffsetDateTime publishedAt,
                           java.time.OffsetDateTime createdAt) {
    }

    @TemplateData
    public record CategoryView(String name, String slug, Long count) {
    }

    @TemplateData
    public record TagView(String name, String slug) {
    }

    @TemplateData
    public record StatsView(long posts, long categories, long tags, long comments) {
    }

    private record LocalCacheEntry(Object value, long expiresAtMillis) {
    }
}
