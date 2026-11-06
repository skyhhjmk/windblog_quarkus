package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.CspNonceContext;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.CanonicalUrlService;
import com.biliwind.blog.service.ConfigTemplateData;
import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
@io.smallrye.common.annotation.Blocking
public class IndexController {

    private static final int PAGE_SIZE = 10;

    /**
     * Machine-readable resources advertised on the homepage for agent discovery
     * (RFC 8288 Link headers, RFC 9727 section 3). Targets stay origin-relative so
     * the same header is valid on every regional domain that serves the site.
     */
    static final String AGENT_DISCOVERY_LINKS = String.join(", ",
            "</.well-known/api-catalog>; rel=\"api-catalog\"",
            "</api/public/openapi>; rel=\"service-desc\"; type=\"application/vnd.oai.openapi+json;version=3.1\"",
            "</api/public/docs>; rel=\"service-doc\"; type=\"text/html\"",
            "</.well-known/api-catalog>; rel=\"describedby\"; type=\"application/linkset+json\"");

    private static final long RENDERED_PAGE_CACHE_TTL_MILLIS = 1000L;
    private static final ConcurrentHashMap<String, RenderedPageCache> RENDERED_PAGE_CACHE = new ConcurrentHashMap<>();

    public static void clearRenderedPageCache() {
        RENDERED_PAGE_CACHE.clear();
    }

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

    @Inject
    com.biliwind.blog.service.StructuredDataService structuredDataService;

    @Inject
    CspNonceContext cspNonceContext;

    @Inject
    I18n i18n;

    @Inject
    CanonicalUrlService canonicalUrlService;

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

        String requestPath = languageContext.getPath();
        if (subPage == 1 && requestPath != null && requestPath.startsWith("/page/")) {
            // /page/1 (and /page/0, which is clamped to 1) duplicates the home page.
            // Use the canonical origin: a relative Location is resolved against the
            // proxy's internal scheme, which would redirect HTTPS visitors to HTTP.
            return Response.status(Response.Status.MOVED_PERMANENTLY)
                    .location(java.net.URI.create(
                            canonicalUrlService.buildLocalizedPath("/", languageContext.getLang())))
                    .build();
        }

        String language = languageContext.getLang();
        String currentRegion = regionContext.getCurrentRegion().getCode();
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        String renderedPageCacheKey = buildRenderedPageCacheKey(
                "index:v4:" + subPage + ":" + language + ":" + currentRegion, pjaxRequest);

        com.biliwind.blog.service.PublicCacheRefreshService.PublicIndexPageSnapshot pageCache =
                publicCacheRefreshService.findPublishedIndexPage(subPage, PAGE_SIZE, language, currentRegion);

        long totalPostsCount = pageCache.totalPostsCount();
        long totalPages = pageCache.totalPages();
        if (subPage > totalPages) {
            // Do not serve an empty page with a 200 status: it is a soft 404.
            throw new jakarta.ws.rs.NotFoundException("Page not found: " + subPage);
        }

        RenderedPageCache cachedPage = getRenderedPage(renderedPageCacheKey);
        if (cachedPage != null) {
            String currentNonce = cspNonceContext.getNonce();
            return buildHtmlResponse(replaceNonce(cachedPage.html, cachedPage.nonce, currentNonce));
        }
        List<IndexPostItem> postItems = toIndexItems(pageCache.posts(), language);

        Template template;
        if (pjaxRequest) {
            template = indexContent;
        } else {
            template = index;
        }

        String pageTitle = homepageTitle(subPage);
        String renderedHtml = template
                .data("jsonLd", structuredDataService.home(subPage, language))
                .data("pageTitle", pageTitle)
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
                .data("prevPageUrl", subPage <= 1 ? languageContext.homeUrl()
                        : (subPage == 2 ? languageContext.homeUrl()
                        : languageContext.localize("/page/" + (subPage - 1))))
                .data("nextPageUrl", totalPages <= 1 || subPage >= totalPages
                        ? (totalPages == 1 ? languageContext.homeUrl()
                        : languageContext.localize("/page/" + totalPages))
                        : languageContext.localize("/page/" + (subPage + 1)))
                .data("firstPageUrl", languageContext.homeUrl())
                .data("lastPageUrl", totalPages == 1 ? languageContext.homeUrl()
                        : languageContext.localize("/page/" + totalPages))
                .data("paginationPages", buildPaginationPages(subPage, totalPages))
                .data("paginationN", "n".repeat(Math.min((int) totalPages, 7)))
                .data("pjaxRequest", pjaxRequest)
                .data("posts", postItems)
                .render();

        setRenderedPageHtml(renderedPageCacheKey, renderedHtml, cspNonceContext.getNonce());
        return buildHtmlResponse(renderedHtml);
    }

    private String homepageTitle(int subPage) {
        StringBuilder title = new StringBuilder(ConfigTemplateData.siteTitle());
        String subtitle = ConfigTemplateData.siteSubtitle();
        // Site title and subtitle are single configured values, so they form the
        // brand part of every language's homepage title.
        if (subtitle != null && !subtitle.isBlank()) {
            title.append(" - ").append(subtitle.trim());
        }
        if (subPage > 1) {
            title.append(" - ").append(i18n.format("index.page", subPage));
        }
        return title.toString();
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

    private RenderedPageCache getRenderedPage(String renderedPageCacheKey) {
        RenderedPageCache renderedPageCache = RENDERED_PAGE_CACHE.get(renderedPageCacheKey);
        if (renderedPageCache == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (renderedPageCache.expiresAtMillis <= now) {
            RENDERED_PAGE_CACHE.remove(renderedPageCacheKey);
            return null;
        }

        return renderedPageCache;
    }

    private void setRenderedPageHtml(String renderedPageCacheKey, String renderedHtml, String nonce) {
        long expiresAtMillis = System.currentTimeMillis() + RENDERED_PAGE_CACHE_TTL_MILLIS;
        RenderedPageCache renderedPageCache = new RenderedPageCache(renderedHtml, nonce, expiresAtMillis);
        RENDERED_PAGE_CACHE.put(renderedPageCacheKey, renderedPageCache);
    }

    private String replaceNonce(String html, String previousNonce, String currentNonce) {
        if (previousNonce == null || previousNonce.isBlank() || currentNonce == null || currentNonce.isBlank()) {
            return html;
        }
        return html.replace("nonce=\"" + previousNonce + "\"", "nonce=\"" + currentNonce + "\"");
    }

    private Response buildHtmlResponse(String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .header("Cache-Control", "no-cache")
                .header("Link", AGENT_DISCOVERY_LINKS)
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
            List<com.biliwind.blog.service.PublicCacheRefreshService.PublicCategorySnapshot> categorySnapshots =
                    snapshot.categories() == null ? List.of() : snapshot.categories();
            List<CategoryItem> categories = categorySnapshots.stream()
                    .map(category -> new CategoryItem(
                            LanguageHelper.resolveLocalizedValue(category.name(), language), category.slug()))
                    .toList();
            if (categories.isEmpty() && snapshot.categorySlug() != null) {
                categories = List.of(new CategoryItem(categoryName, categorySlug));
            }
            result.add(new IndexPostItem(
                    snapshot.slug(), title, summary, snapshot.aiSummary(), aiSummaryStatusValue,
                    snapshot.publishedAt(), snapshot.createdAt(), categoryName, categorySlug,
                    categories, snapshot.authorName(), tags, snapshot.coverImageUrl()));
        }
        return result;
    }

    @TemplateData
    @RegisterForReflection
    public record TagItem(String name, String slug) {}

    @TemplateData
    @RegisterForReflection
    public record CategoryItem(String name, String slug) {}

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
            List<CategoryItem> categories,
            String authorName,
            List<TagItem> tags,
            String coverImageUrl
    ) {
    }

    private record RenderedPageCache(
            String html,
            String nonce,
            long expiresAtMillis
    ) {
    }
}
