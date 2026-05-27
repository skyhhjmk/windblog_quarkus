package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 首页控制器
 */
@Path("/")
public class IndexController {

    private static final int PAGE_SIZE = 10;
    private static final long LOCAL_CACHE_TTL_MILLIS = 3000L;
    private static final ConcurrentHashMap<String, LocalIndexPageCache> LOCAL_INDEX_PAGE_CACHE = new ConcurrentHashMap<>();

    @Inject
    @Location("blog/index.html")
    Template index;

    @Inject
    @Location("blog/index.content.html")
    Template indexContent;

    @Inject
    LanguageContext languageContext;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @Inject
    RegionContext regionContext;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders httpHeaders) {
        return subPage(1, httpHeaders);
    }

    @Path("/page/{subPage}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subPage(@PathParam("subPage") Integer subPage,
                                    @Context HttpHeaders httpHeaders) {
        if (subPage == null) {
            subPage = 1;
        }
        if (subPage < 1) {
            subPage = 1;
        }

        String language = languageContext.getLang();
        String currentRegion = regionContext.getCurrentRegion().getCode();
        String cacheKey = com.biliwind.blog.common.CacheService.Keys.indexPage(subPage, language + ":" + currentRegion);

        IndexPageCache pageCache = getIndexPageCache(cacheKey, subPage, language, currentRegion);

        long totalPostsCount = pageCache.totalPostsCount;
        long totalPages = pageCache.totalPages;
        List<IndexPostItem> postItems = pageCache.postItems;

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);

        Template template;
        if (pjaxRequest) {
            template = indexContent;
        } else {
            template = index;
        }

        return template
                .data("language", language)
                .data("subPage", subPage)
                .data("pageSize", PAGE_SIZE)
                .data("totalPosts", totalPostsCount)
                .data("totalPages", totalPages)
                .data("hasPrevPage", subPage > 1)
                .data("hasNextPage", subPage < totalPages)
                .data("prevPage", Math.max(1, subPage - 1))
                .data("nextPage", Math.min(totalPages, subPage + 1))
                .data("pjaxRequest", pjaxRequest)
                .data("posts", postItems);
    }

    private IndexPageCache getIndexPageCache(String cacheKey, int subPage, String language, String currentRegion) {
        long now = System.currentTimeMillis();
        LocalIndexPageCache localCache = LOCAL_INDEX_PAGE_CACHE.get(cacheKey);
        if (localCache != null) {
            if (localCache.expiresAtMillis > now) {
                return localCache.pageCache;
            }
        }

        synchronized (LOCAL_INDEX_PAGE_CACHE) {
            now = System.currentTimeMillis();
            localCache = LOCAL_INDEX_PAGE_CACHE.get(cacheKey);
            if (localCache != null) {
                if (localCache.expiresAtMillis > now) {
                    return localCache.pageCache;
                }
            }

            IndexPageCache pageCache = loadIndexPageCache(cacheKey, subPage, language, currentRegion);
            LocalIndexPageCache nextLocalCache = new LocalIndexPageCache(pageCache, now + LOCAL_CACHE_TTL_MILLIS);
            LOCAL_INDEX_PAGE_CACHE.put(cacheKey, nextLocalCache);
            return pageCache;
        }
    }

    private IndexPageCache loadIndexPageCache(String cacheKey, int subPage, String language, String currentRegion) {
        Optional<IndexPageCache> cached = Optional.empty();
        if (subPage <= 5) {
            cached = cacheService.get(cacheKey, IndexPageCache.class);
        }

        if (cached.isPresent()) {
            return cached.get();
        }

        IndexPageCache pageCache = queryIndexPageCache(subPage, language, currentRegion);

        if (subPage <= 5) {
            cacheService.set(cacheKey, pageCache, java.time.Duration.ofMinutes(30));
        }

        return pageCache;
    }

    private IndexPageCache queryIndexPageCache(int subPage, String language, String currentRegion) {
        Map<String, Object> parameters = Map.of(
                "status", PostStatus.PUBLISHED,
                "regionPattern", "%\"" + currentRegion + "\"%"
        );

        PanacheQuery<Post> postQuery = Post.find(
                "status = :status and deletedAt is null and visibility = 0 and publishedRevision is not null and (visibilityRegions is null or cast(visibilityRegions as String) like :regionPattern) order by publishedAt desc nulls last, createdAt desc",
                parameters
        );

        long totalPostsCount = postQuery.count();
        List<Post> posts = postQuery.page(Page.of(subPage - 1, PAGE_SIZE)).list();

        List<Long> postIds = new ArrayList<>();
        for (Post post : posts) {
            postIds.add(post.id);
        }

        Map<Long, List<PostTag>> tagsMap = new java.util.HashMap<>();
        if (!postIds.isEmpty()) {
            List<PostTag> allTags = PostTag.find("post.id in ?1", postIds).list();
            for (PostTag postTag : allTags) {
                Long postId = postTag.post.id;
                List<PostTag> tagsForPost = tagsMap.get(postId);
                if (tagsForPost == null) {
                    tagsForPost = new ArrayList<>();
                    tagsMap.put(postId, tagsForPost);
                }
                tagsForPost.add(postTag);
            }
        }

        long totalPages;
        if (totalPostsCount == 0) {
            totalPages = 1;
        } else {
            totalPages = (long) Math.ceil((double) totalPostsCount / PAGE_SIZE);
        }

        List<IndexPostItem> postItems = new ArrayList<>();
        for (Post post : posts) {
            List<PostTag> tagsForPost = tagsMap.get(post.id);
            if (tagsForPost == null) {
                tagsForPost = new ArrayList<>();
            }
            IndexPostItem postItem = toIndexItem(post, language, tagsForPost);
            postItems.add(postItem);
        }

        return new IndexPageCache(postItems, totalPostsCount, totalPages);
    }

    private IndexPostItem toIndexItem(Post post, String language, List<PostTag> postTags) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, language);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, language);

        if (title == null) {
            title = post.slug;
        } else if (title.isBlank()) {
            title = post.slug;
        }

        if (summary == null) {
            summary = "暂无摘要";
        } else if (summary.isBlank()) {
            summary = "暂无摘要";
        }

        Category category = post.category;
        String categoryName;
        if (category != null) {
            categoryName = LanguageHelper.resolveLocalizedValue(category.name, language);
        } else {
            categoryName = "未分类";
        }

        List<TagItem> tags = new ArrayList<>();
        for (PostTag postTag : postTags) {
            String tagName = LanguageHelper.resolveLocalizedValue(postTag.tag.name, language);
            TagItem tagItem = new TagItem(tagName, postTag.tag.slug);
            tags.add(tagItem);
        }

        int aiSummaryStatusValue = 0;
        if (post.aiSummaryStatus != null) {
            aiSummaryStatusValue = post.aiSummaryStatus.intValue();
        }

        String authorName = "Unknown";
        if (post.user != null) {
            authorName = post.user.username;
        }

        String categorySlug = "uncategorized";
        if (category != null) {
            categorySlug = category.slug;
        }

        return new IndexPostItem(
                post.id,
                post.slug,
                title,
                summary,
                post.aiSummary,
                aiSummaryStatusValue,
                post.publishedAt,
                post.createdAt,
                categoryName,
                categorySlug,
                authorName,
                tags
        );
    }

    @TemplateData
    public record TagItem(String name, String slug) {}

    @TemplateData
    public record IndexPostItem(
            Long id,
            String slug,
            String title,
            String summary,
            Map<String, String> aiSummary,
            Integer aiSummaryStatus,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            String categoryName,
            String categorySlug,
            String authorName,
            List<TagItem> tags
    ) {
    }

    private record IndexPageCache(
            List<IndexPostItem> postItems,
            long totalPostsCount,
            long totalPages
    ) {
    }

    private record LocalIndexPageCache(
            IndexPageCache pageCache,
            long expiresAtMillis
    ) {
    }
}

