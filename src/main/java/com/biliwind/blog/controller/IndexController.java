package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
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
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

/**
 * 首页控制器
 */
@Path("/")
public class IndexController {

    private static final int PAGE_SIZE = 10;
    private static final long RENDERED_PAGE_CACHE_TTL_MILLIS = 1000L;
    private static final ConcurrentHashMap<String, RenderedPageCache> RENDERED_PAGE_CACHE = new ConcurrentHashMap<>();

    @Inject
    @Location("blog/index.html")
    Template index;

    @Inject
    @Location("blog/index.content.html")
    Template indexContent;

    @Inject
    LanguageContext languageContext;

    @Inject
    com.biliwind.blog.service.PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    RegionContext regionContext;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index(@Context HttpHeaders httpHeaders) {
        return subPage(1, httpHeaders);
    }

    @Path("/page/{subPage}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response subPage(@PathParam("subPage") Integer subPage,
                            @Context HttpHeaders httpHeaders) {
        if (subPage == null) {
            subPage = 1;
        }
        if (subPage < 1) {
            subPage = 1;
        }

        String language = languageContext.getLang();
        String currentRegion = regionContext.getCurrentRegion().getCode();
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        String renderedPageCacheKey = buildRenderedPageCacheKey(
                "index:v2:" + subPage + ":" + language + ":" + currentRegion, pjaxRequest);

        String cachedHtml = getRenderedPageHtml(renderedPageCacheKey);
        if (cachedHtml != null) {
            return buildHtmlResponse(cachedHtml);
        }

        com.biliwind.blog.service.PublicCacheRefreshService.PublicIndexPageSnapshot pageCache =
                publicCacheRefreshService.findPublishedIndexPage(subPage, PAGE_SIZE, language, currentRegion);

        long totalPostsCount = pageCache.totalPostsCount();
        long totalPages = pageCache.totalPages();
        List<IndexPostItem> postItems = toIndexItems(pageCache.posts(), language);

        Template template;
        if (pjaxRequest) {
            template = indexContent;
        } else {
            template = index;
        }

        String renderedHtml = template
                .data("language", language)
                .data("subPage", subPage)
                .data("currentPage", subPage)
                .data("pageSize", PAGE_SIZE)
                .data("totalPosts", totalPostsCount)
                .data("totalPages", totalPages)
                .data("hasPrevPage", subPage > 1)
                .data("hasNextPage", subPage < totalPages)
                .data("prevPage", Math.max(1, subPage - 1))
                .data("nextPage", Math.min(totalPages, subPage + 1))
                .data("prevPageUrl", subPage <= 1 ? "/" : (subPage == 2 ? "/" : "/page/" + (subPage - 1)))
                .data("nextPageUrl", totalPages <= 1 || subPage >= totalPages
                        ? (totalPages == 1 ? "/" : "/page/" + totalPages)
                        : "/page/" + (subPage + 1))
                .data("firstPageUrl", "/")
                .data("lastPageUrl", totalPages == 1 ? "/" : "/page/" + totalPages)
                .data("paginationPages", buildPaginationPages(subPage, totalPages))
                .data("paginationN", "n".repeat(Math.min((int) totalPages, 7)))
                .data("pjaxRequest", pjaxRequest)
                .data("posts", postItems)
                .render();

        setRenderedPageHtml(renderedPageCacheKey, renderedHtml);
        return buildHtmlResponse(renderedHtml);
    }

    private List<PaginationPage> buildPaginationPages(int currentPage, long totalPages) {
        int lastPage = (int) totalPages;
        int firstPage = paginationWindowStart(currentPage, lastPage);
        int lastVisiblePage = Math.min(lastPage, firstPage + 6);
        return IntStream.rangeClosed(firstPage, lastVisiblePage)
                .mapToObj(page -> new PaginationPage(page,
                        page == 1 ? "/" : "/page/" + page,
                        page == currentPage))
                .toList();
    }

    private int paginationWindowStart(int currentPage, int totalPages) {
        return Math.min(Math.max(1, currentPage - 3), Math.max(1, totalPages - 6));
    }

    private String buildRenderedPageCacheKey(String cacheKey, boolean pjaxRequest) {
        String cacheType;
        if (pjaxRequest) {
            cacheType = "pjax";
        } else {
            cacheType = "full";
        }
        return cacheType + ":" + cacheKey;
    }

    private String getRenderedPageHtml(String renderedPageCacheKey) {
        RenderedPageCache renderedPageCache = RENDERED_PAGE_CACHE.get(renderedPageCacheKey);
        if (renderedPageCache == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (renderedPageCache.expiresAtMillis <= now) {
            RENDERED_PAGE_CACHE.remove(renderedPageCacheKey);
            return null;
        }

        return renderedPageCache.html;
    }

    private void setRenderedPageHtml(String renderedPageCacheKey, String renderedHtml) {
        long expiresAtMillis = System.currentTimeMillis() + RENDERED_PAGE_CACHE_TTL_MILLIS;
        RenderedPageCache renderedPageCache = new RenderedPageCache(renderedHtml, expiresAtMillis);
        RENDERED_PAGE_CACHE.put(renderedPageCacheKey, renderedPageCache);
    }

    private Response buildHtmlResponse(String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .header("Cache-Control", "no-cache")
                .build();
    }

    private List<IndexPostItem> toIndexItems(
            List<com.biliwind.blog.service.PublicCacheRefreshService.PublicPostListSnapshot> snapshots,
            String language) {
        List<IndexPostItem> result = new ArrayList<>();
        for (com.biliwind.blog.service.PublicCacheRefreshService.PublicPostListSnapshot snapshot : snapshots) {
            String title = LanguageHelper.resolveLocalizedValue(snapshot.title(), language);
            String summary = LanguageHelper.resolveLocalizedValue(snapshot.summary(), language);

            if (title == null || title.isBlank()) {
                title = snapshot.slug();
            }

            if (summary == null || summary.isBlank()) {
                summary = "暂无摘要";
            }

            List<TagItem> tags = new ArrayList<>();
            for (com.biliwind.blog.service.PublicCacheRefreshService.PublicTagSnapshot tag : snapshot.tags()) {
                String tagName = LanguageHelper.resolveLocalizedValue(tag.name(), language);
                if (tagName == null || tagName.isBlank()) {
                    tagName = tag.slug();
                }
                TagItem tagItem = new TagItem(tagName, tag.slug());
                tags.add(tagItem);
            }

            int aiSummaryStatusValue = 0;
            if (snapshot.aiSummaryStatus() != null) {
                aiSummaryStatusValue = snapshot.aiSummaryStatus().intValue();
            }

            String categoryName = LanguageHelper.resolveLocalizedValue(snapshot.categoryName(), language);
            if (categoryName == null || categoryName.isBlank()) {
                categoryName = "未分类";
            }
            String categorySlug = snapshot.categorySlug() == null ? "uncategorized" : snapshot.categorySlug();
            result.add(new IndexPostItem(
                    snapshot.slug(), title, summary, snapshot.aiSummary(), aiSummaryStatusValue,
                    snapshot.publishedAt(), snapshot.createdAt(), categoryName, categorySlug,
                    snapshot.authorName(), tags));
        }
        return result;
    }

    @TemplateData
    public record TagItem(String name, String slug) {}

    @TemplateData
    public record IndexPostItem(
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

    private record RenderedPageCache(
            String html,
            long expiresAtMillis
    ) {
    }
}
