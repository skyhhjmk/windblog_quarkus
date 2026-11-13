package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.PageIdentityContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.PageIdentityService;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.PublicCacheRefreshService;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateInstance;
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

@Path("/tag")
@io.smallrye.common.annotation.Blocking
public class TagController {

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

        PublicCacheRefreshService.PublicTagListPage pageData = safeKeyword.isBlank()
                ? publicCacheRefreshService.findTagListPage(requestedPage, lang)
                : publicCacheRefreshService.loadTagListPage(requestedPage, lang, safeKeyword);
        int currentPage = pageData.currentPage();
        int totalPages = pageData.totalPages();
        List<TagListItem> pageItems = pageData.tags().stream()
                .map(this::toTagListItem)
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        String pageTitle = safeKeyword.isBlank()
                ? i18n.t("tag.title")
                : i18n.format("tag.filtered_title", safeKeyword);
        String canonicalPath = "/tag";
        if (safeKeyword.isBlank() && currentPage > 1) {
            canonicalPath = canonicalPath + "?page=" + currentPage;
        }
        pageIdentityContext.set(pageIdentityService.page(canonicalPath, lang));
        return pageRenderer.render(PageView.TAG, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("pjaxRequest", pjaxRequest)
                .put("jsonLd", structuredDataService.collection(i18n.t("tag.title"), "/tag", lang,
                        List.of(new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                i18n.t("tag.title"), "/tag"))))
                .put("detailMode", false)
                .put("pageTitle", withPageSuffix(pageTitle, currentPage))
                .put("pageDescription", i18n.t("tag.list_description"))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / tags")
                .put("keyword", safeKeyword)
                .put("totalCount", pageData.totalCount())
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildTagListUrl(safeKeyword, Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildTagListUrl(safeKeyword, Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildTagListUrl(safeKeyword, 1))
                .put("lastPageUrl", buildTagListUrl(safeKeyword, totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildTagListUrl(safeKeyword, pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("tags", pageItems));
    }

    @GET
    @Path("/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance detail(@PathParam("slug") String slug,
                                   @QueryParam("page") @DefaultValue("1") Integer page,
                                   @Context HttpHeaders httpHeaders) {
        String normalizedSlug = normalizeSlug(slug);
        int requestedPage = Math.min(page == null || page < 1 ? 1 : page, CACHEABLE_PAGE_LIMIT);
        String lang = languageContext.getLang();

        PublicCacheRefreshService.PublicTagDetailPage pageData =
                publicCacheRefreshService.findTagDetailPage(
                                normalizedSlug, requestedPage, lang,
                                regionContext.getCurrentRegion().getCode())
                        .orElseThrow(() -> new NotFoundException("Tag not found: " + normalizedSlug));
        int currentPage = pageData.currentPage();
        int totalPages = pageData.totalPages();

        TagDetailItem currentTag = toTagDetailItem(pageData.tag(), pageData.postCount());
        List<TagPostItem> postItems = pageData.posts().stream()
                .map(this::toTagPostItem)
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        pageIdentityContext.set(pageIdentityService.page(
                "/tag/" + currentTag.slug() + (currentPage > 1 ? "?page=" + currentPage : ""), lang));
        return pageRenderer.render(PageView.TAG, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("jsonLd", structuredDataService.collection(currentTag.name(),
                        "/tag/" + currentTag.slug(), lang,
                        List.of(new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                        i18n.t("tag.title"), "/tag"),
                                new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                        currentTag.name(), "/tag/" + currentTag.slug()))))
                .put("pjaxRequest", pjaxRequest)
                .put("detailMode", true)
                .put("pageTitle", withPageSuffix(
                        i18n.format("tag.detail_title", currentTag.name()), currentPage))
                .put("pageDescription", pageDescription(pageData.tag()))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / tag / " + currentTag.slug())
                .put("totalCount", pageData.postCount())
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildTagDetailUrl(currentTag.slug(), Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildTagDetailUrl(currentTag.slug(), Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildTagDetailUrl(currentTag.slug(), 1))
                .put("lastPageUrl", buildTagDetailUrl(currentTag.slug(), totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildTagDetailUrl(currentTag.slug(), pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("currentTag", currentTag)
                .put("posts", postItems));
    }

    private TagListItem toTagListItem(PublicCacheRefreshService.PublicTagListItem item) {
        String description = item.description() == null || item.description().isBlank()
                ? i18n.t("tag.list_description")
                : item.description();
        return new TagListItem(item.slug(), item.name(), description, item.postCount(),
                item.createdAtText());
    }

    private TagDetailItem toTagDetailItem(PublicCacheRefreshService.PublicTagDetailItem item,
                                          long postCount) {
        String description = item.description() == null || item.description().isBlank()
                ? i18n.t("tag.list_description")
                : item.description();
        return new TagDetailItem(item.slug(), item.name(), description, postCount,
                item.createdAtText());
    }

    private TagPostItem toTagPostItem(PublicCacheRefreshService.PublicTagPostItem item) {
        return new TagPostItem(item.slug(), item.title(), item.summary(), item.publishedAtText(),
                item.categoryName(),
                item.categories().stream()
                        .map(category -> new CategoryItem(category.name(), category.slug()))
                        .toList(),
                item.tags().stream()
                        .map(tag -> new TagItem(tag.name(), tag.slug()))
                        .toList(),
                item.coverImageUrl(), item.coverThumbhash());
    }

    private String pageDescription(PublicCacheRefreshService.PublicTagDetailItem item) {
        String description = item.description();
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return i18n.format("tag.page_description", item.name());
    }

    @TemplateData
    public record TagItem(String name, String slug) {
    }

    @TemplateData
    public record CategoryItem(String name, String slug) {
    }

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

    private String buildTagListUrl(String keyword, int page) {
        StringBuilder url = new StringBuilder("/tag");
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

    private String buildTagDetailUrl(String slug, int page) {
        StringBuilder url = new StringBuilder("/tag/").append(urlEncode(slug));
        if (page > 1) {
            url.append("?page=").append(page);
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @TemplateData
    public record TagListItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    @TemplateData
    public record TagDetailItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    @TemplateData
    public record TagPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<CategoryItem> categories, List<TagItem> tags, String coverImageUrl, String coverThumbhash) {
        public TagPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<CategoryItem> categories, List<TagItem> tags, String coverImageUrl) {
            this(slug, title, summary, publishedAtText, categoryName, categories, tags, coverImageUrl, null);
        }
    }
}
