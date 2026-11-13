package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.PageIdentityContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.PageIdentityService;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.PublicCacheRefreshService;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Path("/category")
@io.smallrye.common.annotation.Blocking
public class CategoryController {

    /** Bounds the cache key space for arbitrary ?page= values; the loader clamps to the real page count. */
    private static final int CACHEABLE_PAGE_LIMIT = 100;

    @Inject
    com.biliwind.blog.service.StructuredDataService structuredDataService;

    @Inject
    PageRenderer pageRenderer;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

    @Inject
    I18n i18n;

    @Inject
    PageIdentityService pageIdentityService;

    @Inject
    PageIdentityContext pageIdentityContext;

    @Inject
    PublicCacheRefreshService publicCacheRefreshService;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String keyword,
                                 @QueryParam("page") @DefaultValue("1") Integer page,
                                 @Context HttpHeaders httpHeaders) {
        int requestedPage = Math.min(page == null || page < 1 ? 1 : page, CACHEABLE_PAGE_LIMIT);
        String lang = languageContext.getLang();
        String safeKeyword = safe(keyword);

        PublicCacheRefreshService.PublicCategoryListPage pageData = safeKeyword.isBlank()
                ? publicCacheRefreshService.findCategoryListPage(requestedPage, lang)
                : publicCacheRefreshService.loadCategoryListPage(requestedPage, lang, safeKeyword);
        int currentPage = pageData.currentPage();
        int totalPages = pageData.totalPages();
        List<CategoryListItem> pageItems = pageData.categories().stream()
                .map(this::toCategoryListItem)
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        String pageTitle = safeKeyword.isBlank()
                ? i18n.t("category.title")
                : i18n.format("category.filtered_title", safeKeyword);
        String canonicalPath = "/category";
        if (safeKeyword.isBlank() && currentPage > 1) {
            canonicalPath = canonicalPath + "?page=" + currentPage;
        }
        pageIdentityContext.set(pageIdentityService.page(canonicalPath, lang));
        return pageRenderer.render(PageView.CATEGORY, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("pjaxRequest", pjaxRequest)
                .put("jsonLd", structuredDataService.collection(i18n.t("category.title"), "/category", lang,
                        List.of(new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                i18n.t("category.title"), "/category"))))
                .put("detailMode", false)
                .put("pageTitle", withPageSuffix(pageTitle, currentPage))
                .put("pageDescription", i18n.t("category.list_description"))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / categories")
                .put("keyword", safeKeyword)
                .put("totalCount", pageData.totalCount())
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildCategoryListUrl(safeKeyword, Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildCategoryListUrl(safeKeyword, Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildCategoryListUrl(safeKeyword, 1))
                .put("lastPageUrl", buildCategoryListUrl(safeKeyword, totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildCategoryListUrl(safeKeyword, pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("categories", pageItems));
    }

    @GET
    @Path("/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance detail(@PathParam("slug") String slug,
                                   @QueryParam("page") @DefaultValue("1") Integer page,
                                   @Context HttpHeaders httpHeaders) {
        String normalizedSlug = normalizeSlug(slug);
        String lang = languageContext.getLang();
        int requestedPage = Math.min(page == null || page < 1 ? 1 : page, CACHEABLE_PAGE_LIMIT);

        PublicCacheRefreshService.PublicCategoryDetailPage pageData =
                publicCacheRefreshService.findCategoryDetailPage(
                                normalizedSlug, requestedPage, lang,
                                regionContext.getCurrentRegion().getCode())
                        .orElseThrow(() -> new NotFoundException("Category not found: " + normalizedSlug));
        int currentPage = pageData.currentPage();
        int totalPages = pageData.totalPages();

        CategoryDetailItem currentCategory = toCategoryDetailItem(pageData.category());
        List<CategoryListItem> childItems = pageData.children().stream()
                .map(this::toCategoryListItem)
                .toList();
        List<CategoryBreadcrumb> breadcrumbs = pageData.breadcrumbs().stream()
                .map(item -> new CategoryBreadcrumb(item.slug(), item.name(), item.url()))
                .toList();
        List<CategoryPostItem> postItems = pageData.posts().stream()
                .map(this::toCategoryPostItem)
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        pageIdentityContext.set(pageIdentityService.page(
                "/category/" + currentCategory.slug() + (currentPage > 1 ? "?page=" + currentPage : ""), lang));
        return pageRenderer.render(PageView.CATEGORY, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("jsonLd", structuredDataService.collection(currentCategory.name(),
                        "/category/" + currentCategory.slug(), lang,
                        breadcrumbs.stream().map(item ->
                                new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                        item.name(), item.url())).toList()))
                .put("pjaxRequest", pjaxRequest)
                .put("detailMode", true)
                .put("pageTitle", withPageSuffix(
                        i18n.format("category.detail_title", currentCategory.name()), currentPage))
                .put("pageDescription", pageDescription(pageData.category()))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / category / " + currentCategory.slug())
                .put("totalCount", childItems.size())
                .put("postCount", pageData.postCount())
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildCategoryDetailUrl(currentCategory.slug(), Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildCategoryDetailUrl(currentCategory.slug(), Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildCategoryDetailUrl(currentCategory.slug(), 1))
                .put("lastPageUrl", buildCategoryDetailUrl(currentCategory.slug(), totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildCategoryDetailUrl(currentCategory.slug(), pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("currentCategory", currentCategory)
                .put("breadcrumbs", breadcrumbs)
                .put("children", childItems)
                .put("posts", postItems));
    }

    private CategoryListItem toCategoryListItem(PublicCacheRefreshService.PublicCategoryListItem item) {
        String description = item.description() == null || item.description().isBlank()
                ? i18n.t("category.list_description")
                : item.description();
        return new CategoryListItem(item.slug(), item.name(), description, item.path(),
                item.childCount(), item.postCount(), item.createdAtText());
    }

    private CategoryDetailItem toCategoryDetailItem(PublicCacheRefreshService.PublicCategoryDetailItem item) {
        String description = item.description() == null || item.description().isBlank()
                ? i18n.t("category.list_description")
                : item.description();
        return new CategoryDetailItem(item.slug(), item.name(), description, item.path(),
                item.childCount(), item.postCount(), item.createdAtText());
    }

    private CategoryPostItem toCategoryPostItem(PublicCacheRefreshService.PublicCategoryPostItem item) {
        return new CategoryPostItem(item.slug(), item.title(), item.summary(), item.publishedAtText(),
                item.categoryName(),
                item.categories().stream()
                        .map(category -> new CategoryItem(category.name(), category.slug()))
                        .toList(),
                item.tags().stream()
                        .map(tag -> new TagItem(tag.name(), tag.slug()))
                        .toList(),
                item.coverImageUrl(), item.coverThumbhash());
    }

    private String pageDescription(PublicCacheRefreshService.PublicCategoryDetailItem item) {
        String description = item.description();
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return i18n.format("category.page_description", item.name());
    }

    @RegisterForReflection
    public record TagItem(String name, String slug) {}

    @RegisterForReflection
    public record CategoryItem(String name, String slug) {}

    private String withPageSuffix(String title, int page) {
        return page > 1 ? title + " - " + i18n.format("index.page", page) : title;
    }

    private String normalizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        String value = slug.trim();
        if (value.toLowerCase(Locale.ROOT).endsWith(".html")) {
            value = value.substring(0, value.length() - ".html".length());
        }
        return value;
    }

    private String buildCategoryListUrl(String keyword, int page) {
        StringBuilder url = new StringBuilder("/category");
        boolean hasQuery = false;

        if (!keyword.isBlank()) {
            url.append("?q=").append(urlEncode(keyword));
            hasQuery = true;
        }
        if (page > 1) {
            url.append(hasQuery ? "&" : "?");
            url.append("page=").append(page);
        }
        return languageContext.localize(url.toString());
    }

    private List<PaginationPage> buildPaginationPages(int currentPage, int totalPages,
                                                       java.util.function.IntFunction<String> urlBuilder) {
        int firstPage = paginationWindowStart(currentPage, totalPages);
        int lastVisiblePage = Math.min(totalPages, firstPage + 6);
        return IntStream.rangeClosed(firstPage, lastVisiblePage)
                .mapToObj(page -> new PaginationPage(page, urlBuilder.apply(page), page == currentPage))
                .toList();
    }

    private int paginationWindowStart(int currentPage, int totalPages) {
        return Math.min(Math.max(1, currentPage - 3), Math.max(1, totalPages - 6));
    }

    private String buildCategoryDetailUrl(String slug, int page) {
        String url = "/category/" + urlEncode(slug);
        return languageContext.localize(page > 1 ? url + "?page=" + page : url);
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @RegisterForReflection
    public record CategoryListItem(String slug, String name, String description, String path, long childCount, long postCount, String createdAtText) {
    }

    @RegisterForReflection
    public record CategoryDetailItem(String slug, String name, String description, String path, long childCount, long postCount, String createdAtText) {
    }

    @RegisterForReflection
    public record CategoryBreadcrumb(String slug, String name, String url) {
    }

    @RegisterForReflection
    public record CategoryPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<CategoryItem> categories, List<TagItem> tags, String coverImageUrl, String coverThumbhash) {
        public CategoryPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<CategoryItem> categories, List<TagItem> tags, String coverImageUrl) {
            this(slug, title, summary, publishedAtText, categoryName, categories, tags, coverImageUrl, null);
        }
    }
}
