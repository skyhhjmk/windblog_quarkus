package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.PageIdentityContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.CanonicalUrlService;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PageCachePolicy;
import com.biliwind.blog.service.PageIdentityService;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import com.biliwind.blog.model.ArticleMode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Inject
    PageRenderer pageRenderer;

    @Inject
    LanguageContext languageContext;

    @Inject
    com.biliwind.blog.service.PublicCacheRefreshService publicCacheRefreshService;

    @Inject
    RegionContext regionContext;

    @Inject
    com.biliwind.blog.service.StructuredDataService structuredDataService;

    @Inject
    I18n i18n;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @Inject
    PageIdentityService pageIdentityService;

    @Inject
    PageIdentityContext pageIdentityContext;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response index(@Context HttpHeaders httpHeaders,
                          @Context Request request,
                          @QueryParam("mode") String mode) {
        return subPage(1, httpHeaders, request, mode);
    }

    @Path("/page/{subPage}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response subPage(@PathParam("subPage") Integer subPage,
                            @Context HttpHeaders httpHeaders,
                            @Context Request request,
                            @QueryParam("mode") String mode) {
        if (subPage == null) {
            subPage = 1;
        }
        if (subPage < 1) {
            subPage = 1;
        }

        String normalizedMode = normalizeArticleMode(mode);
        ArticleMode filterMode = parseFilterArticleMode(normalizedMode);

        String requestPath = languageContext.getPath();
        if (subPage == 1 && requestPath != null && requestPath.startsWith("/page/")) {
            // /page/1 (and /page/0, which is clamped to 1) duplicates the home page.
            // Use the canonical origin: a relative Location is resolved against the
            // proxy's internal scheme, which would redirect HTTPS visitors to HTTP.
            String targetPath = canonicalUrlService.buildLocalizedPath("/", languageContext.getLang());
            if (!"all".equals(normalizedMode)) {
                targetPath += (targetPath.contains("?") ? "&" : "?") + "mode=" + normalizedMode;
            }
            return Response.status(Response.Status.MOVED_PERMANENTLY)
                    .location(java.net.URI.create(targetPath))
                    .build();
        }

        String language = languageContext.getLang();
        String currentRegion = regionContext.getCurrentRegion().getCode();
        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        pageIdentityContext.set(pageIdentityService.page(
                requestPath == null || requestPath.isBlank() ? "/" : requestPath, language));

        com.biliwind.blog.service.PublicCacheRefreshService.PublicIndexPageSnapshot pageCache =
                publicCacheRefreshService.findPublishedIndexPage(subPage, PAGE_SIZE, language, currentRegion, filterMode);

        long totalPostsCount = pageCache.totalPostsCount();
        long totalPages = pageCache.totalPages();
        if (subPage > totalPages) {
            // Do not serve an empty page with a 200 status: it is a soft 404.
            throw new jakarta.ws.rs.NotFoundException("Page not found: " + subPage);
        }

        List<IndexPostItem> postItems = toIndexItems(pageCache.posts(), language);

        String pageTitle = homepageTitle(subPage, normalizedMode);
        PageRenderModel model = new PageRenderModel()
                .put("jsonLd", structuredDataService.home(subPage, language))
                .put("pageTitle", pageTitle)
                .put("language", language)
                .put("subPage", subPage)
                .put("currentPage", subPage)
                .put("pageSize", PAGE_SIZE)
                .put("totalPosts", totalPostsCount)
                .put("totalPages", totalPages)
                .put("hasPrevPage", subPage > 1)
                .put("hasNextPage", subPage < totalPages)
                .put("prevPage", Math.max(1, subPage - 1))
                .put("nextPage", Math.min(totalPages, subPage + 1))
                .put("prevPageUrl", buildIndexPageUrl(Math.max(1, subPage - 1), normalizedMode))
                .put("nextPageUrl", totalPages <= 1 || subPage >= totalPages
                        ? buildIndexPageUrl((int) totalPages, normalizedMode)
                        : buildIndexPageUrl(subPage + 1, normalizedMode))
                .put("firstPageUrl", buildIndexPageUrl(1, normalizedMode))
                .put("lastPageUrl", buildIndexPageUrl((int) totalPages, normalizedMode))
                .put("paginationPages", buildPaginationPages(subPage, totalPages, normalizedMode))
                .put("paginationN", "n".repeat(Math.min((int) totalPages, 7)))
                .put("pjaxRequest", pjaxRequest)
                .put("currentMode", normalizedMode)
                .put("filterAllUrl", buildIndexPageUrl(1, "all"))
                .put("filterStandardUrl", buildIndexPageUrl(1, "standard"))
                .put("filterTutorialUrl", buildIndexPageUrl(1, "tutorial"))
                .put("filterResourceUrl", buildIndexPageUrl(1, "resource"))
                .put("posts", postItems)
                .put("pageIdentity", pageIdentityContext.get())
                .put("siteConfig", pageIdentityService.environmentFingerprint())
                .put("region", currentRegion)
                .put("requestLanguage", language);

        EntityTag etag = model.validator();
        Response.ResponseBuilder precondition = request.evaluatePreconditions(etag);
        if (precondition != null) {
            return PageCachePolicy.publicDocument(precondition, etag)
                    .header("Link", AGENT_DISCOVERY_LINKS)
                    .build();
        }

        String renderedHtml = pageRenderer.render(PageView.INDEX, httpHeaders, model).render();
        return PageCachePolicy.publicDocument(Response.ok(renderedHtml)
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")), etag)
                .header("Link", AGENT_DISCOVERY_LINKS)
                .build();
    }

    String normalizeArticleMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "all";
        }
        String lower = mode.trim().toLowerCase();
        if ("standard".equals(lower) || "tutorial".equals(lower) || "resource".equals(lower)) {
            return lower;
        }
        return "all";
    }

    ArticleMode parseFilterArticleMode(String normalizedMode) {
        if ("standard".equals(normalizedMode)) {
            return ArticleMode.STANDARD;
        } else if ("tutorial".equals(normalizedMode)) {
            return ArticleMode.TUTORIAL;
        } else if ("resource".equals(normalizedMode)) {
            return ArticleMode.RESOURCE;
        }
        return null;
    }

    String buildIndexPageUrl(int page, String mode) {
        String base;
        if (page <= 1) {
            base = languageContext.homeUrl();
        } else {
            base = languageContext.localize("/page/" + page);
        }
        if (mode != null && !mode.isBlank() && !"all".equalsIgnoreCase(mode)) {
            return base + (base.contains("?") ? "&" : "?") + "mode=" + mode;
        }
        return base;
    }

    String homepageTitle(int subPage, String mode) {
        StringBuilder title = new StringBuilder(ConfigTemplateData.siteTitle());
        String subtitle = ConfigTemplateData.siteSubtitle();
        // Site title and subtitle are single configured values, so they form the
        // brand part of every language's homepage title.
        if (subtitle != null && !subtitle.isBlank()) {
            title.append(" - ").append(subtitle.trim());
        }
        if ("tutorial".equals(mode)) {
            title.append(" - ").append(i18n.t("index.mode.tutorial"));
        } else if ("resource".equals(mode)) {
            title.append(" - ").append(i18n.t("index.mode.resource"));
        } else if ("standard".equals(mode)) {
            title.append(" - ").append(i18n.t("index.mode.standard"));
        }
        if (subPage > 1) {
            title.append(" - ").append(i18n.format("index.page", subPage));
        }
        return title.toString();
    }

    List<PaginationPage> buildPaginationPages(int currentPage, long totalPages, String mode) {
        int lastPage = (int) totalPages;
        int firstPage = paginationWindowStart(currentPage, lastPage);
        int lastVisiblePage = Math.min(lastPage, firstPage + 6);
        return IntStream.rangeClosed(firstPage, lastVisiblePage)
                .mapToObj(page -> new PaginationPage(page,
                        buildIndexPageUrl(page, mode),
                        page == currentPage))
                .toList();
    }

    private int paginationWindowStart(int currentPage, int totalPages) {
        return Math.min(Math.max(1, currentPage - 3), Math.max(1, totalPages - 6));
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
            String articleMode = snapshot.articleMode() == null ? "standard" : snapshot.articleMode().value();
            String modeBadge = null;
            if ("tutorial".equals(articleMode)) {
                if (snapshot.tutorialMeta() != null && snapshot.tutorialMeta().estimatedMinutes() != null && snapshot.tutorialMeta().estimatedMinutes() > 0) {
                    modeBadge = "教程 · " + snapshot.tutorialMeta().estimatedMinutes() + "分钟";
                } else {
                    modeBadge = "教程";
                }
            } else if ("resource".equals(articleMode)) {
                if (snapshot.resourceMeta() != null && snapshot.resourceMeta().version() != null && !snapshot.resourceMeta().version().isBlank()) {
                    modeBadge = "资源 · " + snapshot.resourceMeta().version();
                } else {
                    modeBadge = "资源";
                }
            }
            result.add(new IndexPostItem(
                    snapshot.slug(), title, summary, snapshot.aiSummary(), aiSummaryStatusValue,
                    snapshot.publishedAt(), snapshot.createdAt(), categoryName, categorySlug,
                    categories, snapshot.authorName(), tags, snapshot.coverImageUrl(), snapshot.coverThumbhash(),
                    articleMode, modeBadge));
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
            String coverImageUrl,
            String coverThumbhash,
            String articleMode,
            String modeBadge
    ) {
        public IndexPostItem(
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
            this(slug, title, summary, aiSummary, aiSummaryStatus, publishedAt, createdAt,
                    categoryName, categorySlug, categories, authorName, tags, coverImageUrl, null, "standard", null);
        }
    }
}
