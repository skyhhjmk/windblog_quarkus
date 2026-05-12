package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
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

/**
 * 首页控制器
 */
@Path("/")
public class IndexController {

    private static final int PAGE_SIZE = 10;

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
        String cacheKey = com.biliwind.blog.common.CacheService.Keys.indexPage(subPage, language);

        long totalPostsCount;
        long totalPages;
        List<IndexPostItem> postItems;

        // 仅缓存前 5 页
        java.util.Optional<IndexPageCache> cached = java.util.Optional.empty();
        if (subPage <= 5) {
            cached = cacheService.get(cacheKey, IndexPageCache.class);
        }

        if (cached.isPresent()) {
            IndexPageCache cache = cached.get();
            postItems = cache.postItems;
            totalPostsCount = cache.totalPostsCount;
            totalPages = cache.totalPages;
        } else {
            // 替换已弃用的 Parameters.with，直接使用 Map.of
            Map<String, Object> parameters = Map.of("status", PostStatus.PUBLISHED);

            PanacheQuery<Post> postQuery = Post.find(
                    "status = :status and deletedAt is null order by publishedAt desc nulls last, createdAt desc",
                    parameters
            );

            totalPostsCount = postQuery.count();
            List<Post> posts = postQuery.page(Page.of(subPage - 1, PAGE_SIZE)).list();

            List<Long> postIds = new ArrayList<>();
            for (Post post : posts) {
                postIds.add(post.id);
            }

            Map<Long, List<PostTag>> tagsMap = new java.util.HashMap<>();
            if (!postIds.isEmpty()) {
                List<PostTag> allTags = PostTag.find("post.id in ?1", postIds).list();
                for (PostTag pt : allTags) {
                    Long pId = pt.post.id;
                    List<PostTag> list = tagsMap.get(pId);
                    if (list == null) {
                        list = new ArrayList<>();
                        tagsMap.put(pId, list);
                    }
                    list.add(pt);
                }
            }

            if (totalPostsCount == 0) {
                totalPages = 1;
            } else {
                totalPages = (long) Math.ceil((double) totalPostsCount / PAGE_SIZE);
            }

            postItems = new ArrayList<>();
            for (Post post : posts) {
                List<PostTag> tagsForPost = tagsMap.get(post.id);
                if (tagsForPost == null) {
                    tagsForPost = new ArrayList<>();
                }
                IndexPostItem postItem = toIndexItem(post, language, tagsForPost);
                postItems.add(postItem);
            }

            // 存入缓存
            if (subPage <= 5) {
                IndexPageCache cacheData = new IndexPageCache(postItems, totalPostsCount, totalPages);
                cacheService.set(cacheKey, cacheData, java.time.Duration.ofMinutes(30));
            }
        }

        Template template;
        if (PjaxHelper.isPjaxRequest(httpHeaders)) {
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
                .data("posts", postItems);
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

    public record TagItem(String name, String slug) {}

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
}

