package com.biliwind.blog.service;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.panache.common.Sort;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateExtension;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SidebarTemplateData {

    public static final String SIDEBAR_LAYOUT_KEY = "sidebar_layout";

    private static final long LOCAL_CACHE_TTL_MILLIS = 10000L;
    private static final int MAX_PUBLIC_CATEGORIES = 200;
    private static final int MAX_PUBLIC_TAGS = 500;
    private static final ConcurrentHashMap<String, LocalCacheEntry> LOCAL_CACHE = new ConcurrentHashMap<>();

    private static final Set<String> GENERAL_WIDGET_TYPES = Set.of(
            "stats",
            "recent_posts",
            "categories",
            "tags",
            "custom_html"
    );

    private static final Set<String> POST_ONLY_WIDGET_TYPES = Set.of(
            "post_access",
            "tutorial_tracker",
            "toc",
            "block_manager",
            "post_attachments",
            "related_store"
    );

    private static LanguageContext getLanguageContext() {
        return jakarta.enterprise.inject.spi.CDI.current().select(LanguageContext.class).get();
    }

    private static com.biliwind.blog.context.RegionContext getRegionContext() {
        return jakarta.enterprise.inject.spi.CDI.current().select(com.biliwind.blog.context.RegionContext.class).get();
    }

    private static CacheService getCacheService() {
        return jakarta.enterprise.inject.spi.CDI.current().select(CacheService.class).get();
    }

    private static ConfigManager getConfigManager() {
        try {
            return jakarta.enterprise.inject.spi.CDI.current().select(ConfigManager.class).get();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<SidebarWidgetView> widgets(String area) {
        ConfigManager configManager = getConfigManager();
        JsonNode layoutConfig = configManager != null ? configManager.get(SIDEBAR_LAYOUT_KEY) : null;
        return resolveWidgets(layoutConfig, area);
    }

    public static List<SidebarWidgetView> resolveWidgets(JsonNode layoutConfig, String area) {
        String normalizedArea = normalizeArea(area);
        boolean isPostArea = "post".equals(normalizedArea);

        JsonNode areaArray = null;
        if (layoutConfig != null && layoutConfig.isObject()) {
            JsonNode areasNode = layoutConfig.get("areas");
            if (areasNode != null && areasNode.isObject()) {
                JsonNode candidate = areasNode.get(normalizedArea);
                if (candidate != null && candidate.isArray()) {
                    areaArray = candidate;
                } else if (!"default".equals(normalizedArea)) {
                    JsonNode fallbackCandidate = areasNode.get("default");
                    if (fallbackCandidate != null && fallbackCandidate.isArray() && !"post".equals(normalizedArea)) {
                        areaArray = fallbackCandidate;
                    }
                }
            }
        }

        if (areaArray == null) {
            return defaultWidgetsForArea(normalizedArea);
        }

        List<SidebarWidgetView> result = new ArrayList<>();
        int index = 0;
        for (JsonNode item : areaArray) {
            index++;
            if (item == null || !item.isObject()) {
                continue;
            }
            String type = item.path("type").asText("").trim().toLowerCase(Locale.ROOT);
            if (!isSupportedWidgetType(type, isPostArea)) {
                continue;
            }
            boolean enabled = !item.has("enabled") || item.path("enabled").asBoolean(true);
            if (!enabled) {
                continue;
            }
            String id = item.path("id").asText("").trim();
            if (id.isEmpty()) {
                id = normalizedArea + "-" + type + "-" + index;
            }
            String title = item.path("title").asText("").trim();
            int defaultLimit = defaultLimitForType(type);
            int limit = clampLimitForType(type, item.path("limit").asInt(defaultLimit));
            boolean showCount = !item.has("showCount") || item.path("showCount").asBoolean(true);
            boolean showDate = !item.has("showDate") || item.path("showDate").asBoolean(true);
            boolean showPosts = !item.has("showPosts") || item.path("showPosts").asBoolean(true);
            boolean showComments = !item.has("showComments") || item.path("showComments").asBoolean(true);
            boolean showCategories = !item.has("showCategories") || item.path("showCategories").asBoolean(true);
            boolean showTags = !item.has("showTags") || item.path("showTags").asBoolean(true);
            String content = item.path("content").asText("");
            boolean showCardWrapper = !item.has("showCardWrapper") || item.path("showCardWrapper").asBoolean(true);

            if ("custom_html".equals(type) && content.isBlank()) {
                continue;
            }

            result.add(new SidebarWidgetView(
                    id,
                    type,
                    true,
                    title,
                    limit,
                    showCount,
                    showDate,
                    showPosts,
                    showComments,
                    showCategories,
                    showTags,
                    content,
                    showCardWrapper
            ));
        }
        return result;
    }

    private static String normalizeArea(String area) {
        if (area == null || area.isBlank()) {
            return "default";
        }
        String lower = area.trim().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "home", "index" -> "home";
            case "archive", "category", "tag", "search" -> "archive";
            case "link", "links" -> "link";
            case "post", "article" -> "post";
            default -> "default";
        };
    }

    private static boolean isSupportedWidgetType(String type, boolean isPostArea) {
        if (GENERAL_WIDGET_TYPES.contains(type)) {
            return true;
        }
        return isPostArea && POST_ONLY_WIDGET_TYPES.contains(type);
    }

    private static int defaultLimitForType(String type) {
        return switch (type) {
            case "recent_posts" -> 5;
            case "categories" -> 20;
            case "tags" -> 50;
            default -> 5;
        };
    }

    private static int clampLimitForType(String type, int rawLimit) {
        return switch (type) {
            case "recent_posts" -> Math.max(1, Math.min(20, rawLimit));
            case "categories" -> Math.max(1, Math.min(MAX_PUBLIC_CATEGORIES, rawLimit));
            case "tags" -> Math.max(1, Math.min(MAX_PUBLIC_TAGS, rawLimit));
            default -> Math.max(1, Math.min(100, rawLimit));
        };
    }

    public static List<SidebarWidgetView> defaultWidgetsForArea(String area) {
        String normalized = normalizeArea(area);
        return switch (normalized) {
            case "home" -> List.of(
                    builtinWidget("home-stats", "stats", 5),
                    builtinWidget("home-recent-posts", "recent_posts", 5),
                    builtinWidget("home-categories", "categories", 20),
                    builtinWidget("home-tags", "tags", 50)
            );
            case "archive" -> List.of(
                    builtinWidget("archive-categories", "categories", 20),
                    builtinWidget("archive-tags", "tags", 50),
                    builtinWidget("archive-recent-posts", "recent_posts", 5),
                    builtinWidget("archive-stats", "stats", 5)
            );
            case "link" -> List.of(
                    builtinWidget("link-stats", "stats", 5),
                    builtinWidget("link-recent-posts", "recent_posts", 5),
                    builtinWidget("link-tags", "tags", 50)
            );
            case "post" -> List.of(
                    builtinWidget("post-access", "post_access", 5),
                    builtinWidget("post-tutorial", "tutorial_tracker", 5),
                    builtinWidget("post-toc", "toc", 5),
                    builtinWidget("post-blocks", "block_manager", 5),
                    builtinWidget("post-attachments", "post_attachments", 5),
                    builtinWidget("post-store", "related_store", 5),
                    builtinWidget("post-recent-posts", "recent_posts", 5),
                    builtinWidget("post-tags", "tags", 50)
            );
            default -> List.of(
                    builtinWidget("default-stats", "stats", 5),
                    builtinWidget("default-recent-posts", "recent_posts", 5),
                    builtinWidget("default-tags", "tags", 50)
            );
        };
    }

    private static SidebarWidgetView builtinWidget(String id, String type, int limit) {
        return new SidebarWidgetView(
                id,
                type,
                true,
                "",
                limit,
                true,
                true,
                true,
                true,
                true,
                true,
                "",
                true
        );
    }

    public static ObjectNode buildDefaultSidebarLayoutNode(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode areas = root.putObject("areas");
        for (String area : List.of("home", "archive", "link", "post", "default")) {
            ArrayNode arr = areas.putArray(area);
            for (SidebarWidgetView w : defaultWidgetsForArea(area)) {
                ObjectNode item = arr.addObject();
                item.put("id", w.id());
                item.put("type", w.type());
                item.put("enabled", w.enabled());
                item.put("title", w.title());
                if ("recent_posts".equals(w.type())) {
                    item.put("limit", w.limit());
                    item.put("showDate", w.showDate());
                } else if ("categories".equals(w.type())) {
                    item.put("limit", w.limit());
                    item.put("showCount", w.showCount());
                } else if ("tags".equals(w.type())) {
                    item.put("limit", w.limit());
                } else if ("stats".equals(w.type())) {
                    item.put("showPosts", w.showPosts());
                    item.put("showComments", w.showComments());
                    item.put("showCategories", w.showCategories());
                    item.put("showTags", w.showTags());
                } else if ("custom_html".equals(w.type())) {
                    item.put("content", w.content());
                    item.put("showCardWrapper", w.showCardWrapper());
                }
            }
        }
        return root;
    }

    @TemplateExtension(namespace = "sidebar")
    public static List<PostView> recentPosts(int limit) {
        int safeLimit = clampLimitForType("recent_posts", limit);
        String lang = getLanguageContext().getLang();
        String region = getRegionContext().getCurrentRegion().getCode();
        String cacheKey = CacheService.Keys.recentPosts(lang, region, safeLimit);
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
                .page(0, safeLimit)
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

        List<Category> categories = Category.find("enabled = true order by path")
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
    public static List<CategoryView> categories(int limit) {
        int safeLimit = clampLimitForType("categories", limit);
        List<CategoryView> all = categories();
        if (all.size() <= safeLimit) {
            return all;
        }
        return all.subList(0, safeLimit);
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
    public static List<TagView> tags(int limit) {
        int safeLimit = clampLimitForType("tags", limit);
        List<TagView> all = tags();
        if (all.size() <= safeLimit) {
            return all;
        }
        return all.subList(0, safeLimit);
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
        long categoryCount = Category.count("enabled", true);
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
    @RegisterForReflection
    public record PostView(String title, String titleInitial, String slug, java.time.OffsetDateTime publishedAt,
                           java.time.OffsetDateTime createdAt) {
    }

    @TemplateData
    @RegisterForReflection
    public record CategoryView(String name, String slug, Long count) {
    }

    @TemplateData
    @RegisterForReflection
    public record TagView(String name, String slug) {
    }

    @TemplateData
    @RegisterForReflection
    public record StatsView(long posts, long categories, long tags, long comments) {
    }

    @TemplateData
    @RegisterForReflection
    public record SidebarWidgetView(
            String id,
            String type,
            boolean enabled,
            String title,
            int limit,
            boolean showCount,
            boolean showDate,
            boolean showPosts,
            boolean showComments,
            boolean showCategories,
            boolean showTags,
            String content,
            boolean showCardWrapper
    ) {
    }

    private record LocalCacheEntry(Object value, long expiresAtMillis) {
    }
}
