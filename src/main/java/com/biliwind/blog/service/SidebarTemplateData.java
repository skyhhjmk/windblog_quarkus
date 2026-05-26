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

public class SidebarTemplateData {

    private static LanguageContext getLanguageContext() {
        return jakarta.enterprise.inject.spi.CDI.current().select(LanguageContext.class).get();
    }

    private static CacheService getCacheService() {
        return jakarta.enterprise.inject.spi.CDI.current().select(CacheService.class).get();
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<PostView> recentPosts(int limit) {
        String lang = getLanguageContext().getLang();
        String cacheKey = CacheService.Keys.recentPosts(lang, limit);

        java.util.Optional<List<PostView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<PostView>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Post> posts = Post.find("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                        Sort.descending("publishedAt").and("createdAt").descending(),
                        com.biliwind.blog.model.PostStatus.PUBLISHED)
                .page(0, limit)
                .list();

        List<PostView> result = new ArrayList<>();
        for (Post p : posts) {
            String title = LanguageHelper.resolveLocalizedValue(p.title, lang);
            String safeTitle = resolvePostTitle(title, p.slug, p.id);
            String titleInitial = resolveTitleInitial(safeTitle);
            result.add(new PostView(p.id, safeTitle, titleInitial, p.slug, p.publishedAt, p.createdAt));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
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

        java.util.Optional<List<CategoryView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<CategoryView>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Category> categories = Category.listAll(Sort.ascending("path"));

        List<CategoryView> result = new ArrayList<>();
        for (Category c : categories) {
            String name = LanguageHelper.resolveLocalizedValue(c.name, lang);
            result.add(new CategoryView(c.id, name, c.slug, c.postCount));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        return result;
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<TagView> tags() {
        String lang = getLanguageContext().getLang();
        String cacheKey = CacheService.Keys.tags(lang);

        java.util.Optional<List<TagView>> cached = getCacheService().get(cacheKey, new com.fasterxml.jackson.core.type.TypeReference<List<TagView>>() {
        });
        if (cached.isPresent()) {
            return cached.get();
        }

        List<Tag> tags = Tag.listAll();

        List<TagView> result = new ArrayList<>();
        for (Tag t : tags) {
            String name = LanguageHelper.resolveLocalizedValue(t.name, lang);
            result.add(new TagView(t.id, name, t.slug));
        }

        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        return result;
    }

    @TemplateExtension(namespace = "sidebar")
    public static StatsView stats() {
        String cacheKey = CacheService.Keys.SIDEBAR_STATS;

        java.util.Optional<StatsView> cached = getCacheService().get(cacheKey, StatsView.class);
        if (cached.isPresent()) {
            return cached.get();
        }

        long postCount = Post.count("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                com.biliwind.blog.model.PostStatus.PUBLISHED);
        long categoryCount = Category.count();
        long tagCount = Tag.count();
        long commentCount = Comment.count();

        StatsView result = new StatsView(postCount, categoryCount, tagCount, commentCount);
        getCacheService().set(cacheKey, result, java.time.Duration.ofHours(1));
        return result;
    }

    @TemplateData
    public record PostView(Long id, String title, String titleInitial, String slug, java.time.OffsetDateTime publishedAt,
                           java.time.OffsetDateTime createdAt) {
    }

    @TemplateData
    public record CategoryView(Long id, String name, String slug, Long count) {
    }

    @TemplateData
    public record TagView(Long id, String name, String slug) {
    }

    @TemplateData
    public record StatsView(long posts, long categories, long tags, long comments) {
    }
}
