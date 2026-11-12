package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostCoverImageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.PageIdentityContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.PageIdentityService;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Path("/category")
@io.smallrye.common.annotation.Blocking
public class CategoryController {

    @Inject
    com.biliwind.blog.service.StructuredDataService structuredDataService;

    private static final int PAGE_SIZE = 12;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

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

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String keyword,
                                 @QueryParam("page") @DefaultValue("1") Integer page,
                                 @Context HttpHeaders httpHeaders) {
        int currentPage = page == null || page < 1 ? 1 : page;
        String lang = languageContext.getLang();
        String safeKeyword = safe(keyword);

        PanacheQuery<Category> categoryQuery;
        if (safeKeyword.isBlank()) {
            categoryQuery = Category.find("enabled = true order by path");
        } else {
            String pattern = "%" + safeKeyword.toLowerCase(Locale.ROOT) + "%";
            categoryQuery = Category.find(
                    "enabled = true and (lower(cast(name as String)) like ?1 or lower(cast(description as String)) like ?1 "
                            + "or lower(slug) like ?1 or lower(path) like ?1) order by path",
                    pattern);
        }

        long totalCount = categoryQuery.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<Category> categories = categoryQuery.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, Long> childCounts = loadChildCounts(categories);
        List<CategoryListItem> pageItems = categories.stream()
                .map(category -> toCategoryListItem(category, lang, childCounts.getOrDefault(category.id, 0L)))
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
                .put("totalCount", totalCount)
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
        int currentPage = page == null || page < 1 ? 1 : page;

        Category entity = Category.find("slug", normalizedSlug).firstResult();
        if (entity == null) {
            throw new NotFoundException("Category not found: " + normalizedSlug);
        }
        if (!entity.enabled) throw new NotFoundException("Category not found: " + normalizedSlug);

        List<Category> children = Category.list("parent.id = ?1 and enabled = true order by createdAt desc", entity.id);
        List<CategoryListItem> childItems = children.stream().map(c -> toCategoryListItem(c, lang)).toList();

        RegionVisibility.PositionalFilter regionFilter =
                RegionVisibility.jpqlFilter(3, regionContext.getCurrentRegion());
        String queryStr = "(category = ?1 or ?1 member of categories) and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null "
                + "and " + regionFilter.fragment() + " "
                + "order by publishedAt desc nulls last, createdAt desc";
        PanacheQuery<Post> query = Post.find(queryStr, entity, PostStatus.PUBLISHED, regionFilter.regionPattern());
        long postCount = query.count();
        int totalPages = postCount == 0 ? 1 : (int) Math.ceil((double) postCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, List<TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        java.util.Map<Long, PostCoverImageHelper.CoverImageInfo> featuredInfos = PostCoverImageHelper.findFeaturedImageInfos(
                posts.stream().map(post -> post.id).toList());
        List<CategoryPostItem> postItems = posts.stream()
                .map(post -> toCategoryPostItem(post, lang, tagsByPost.getOrDefault(post.id, List.of()),
                        featuredInfos.get(post.id)))
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        pageIdentityContext.set(pageIdentityService.page(
                "/category/" + entity.slug + (currentPage > 1 ? "?page=" + currentPage : ""), lang));
        return pageRenderer.render(PageView.CATEGORY, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("jsonLd", structuredDataService.collection(resolveCategoryName(entity, lang), "/category/" + entity.slug, lang,
                        buildBreadcrumbs(entity, lang).stream().map(item ->
                                new com.biliwind.blog.service.StructuredDataService.Breadcrumb(item.name(), item.url())).toList()))
                .put("pjaxRequest", pjaxRequest)
                .put("detailMode", true)
                .put("pageTitle", withPageSuffix(
                        i18n.format("category.detail_title", resolveCategoryName(entity, lang)), currentPage))
                .put("pageDescription", categoryDescription(entity, lang))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / category / " + entity.slug)
                .put("totalCount", childItems.size())
                .put("postCount", postCount)
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildCategoryDetailUrl(entity.slug, Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildCategoryDetailUrl(entity.slug, Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildCategoryDetailUrl(entity.slug, 1))
                .put("lastPageUrl", buildCategoryDetailUrl(entity.slug, totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildCategoryDetailUrl(entity.slug, pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("currentCategory", toCategoryDetailItem(entity, lang))
                .put("breadcrumbs", buildBreadcrumbs(entity, lang))
                .put("children", childItems)
                .put("posts", postItems));
    }

    private CategoryPostItem toCategoryPostItem(Post post, String lang, List<TagItem> tags,
                                                PostCoverImageHelper.CoverImageInfo featuredInfo) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "暂无摘要";
        }
        
        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;
        List<CategoryItem> categories = post.categories.stream()
                .filter(category -> category != null && category.enabled)
                .map(category -> new CategoryItem(
                        LanguageHelper.resolveLocalizedValue(category.name, lang), category.slug))
                .toList();
        if (categories.isEmpty() && post.category != null && post.category.enabled) {
            categories = List.of(new CategoryItem(
                    LanguageHelper.resolveLocalizedValue(post.category.name, lang), post.category.slug));
        }
        PostCoverImageHelper.CoverImageInfo coverInfo =
                PostCoverImageHelper.resolveCoverImageInfo(post, lang, featuredInfo);
        return new CategoryPostItem(post.slug, title, summary, formatDate(date),
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", categories, tags,
                coverInfo.url(), coverInfo.thumbhash());
    }

    @RegisterForReflection
    public record TagItem(String name, String slug) {}

    @RegisterForReflection
    public record CategoryItem(String name, String slug) {}

    private java.util.Map<Long, List<TagItem>> loadTagsByPost(List<Post> posts, String lang) {
        if (posts.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> postIds = posts.stream().map(post -> post.id).toList();
        java.util.Map<Long, List<TagItem>> tagsByPost = new java.util.HashMap<>();
        List<PostTag> postTags = PostTag.find("post.id in ?1", postIds).list();
        for (PostTag postTag : postTags) {
            tagsByPost.computeIfAbsent(postTag.post.id, ignored -> new ArrayList<>())
                    .add(new TagItem(LanguageHelper.resolveLocalizedValue(postTag.tag.name, lang), postTag.tag.slug));
        }
        return tagsByPost;
    }

    private CategoryListItem toCategoryListItem(Category category, String lang) {
        return toCategoryListItem(category, lang, Category.count("parent.id", category.id));
    }

    private CategoryListItem toCategoryListItem(Category category, String lang, long childCount) {
        String name = resolveCategoryName(category, lang);
        String description = resolveCategoryDescription(category, lang);
        return new CategoryListItem(
                category.slug,
                name,
                description,
                safe(category.path),
                childCount,
                category.postCount,
                formatDate(category.createdAt)
        );
    }

    private java.util.Map<Long, Long> loadChildCounts(List<Category> categories) {
        if (categories.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> categoryIds = categories.stream().map(category -> category.id).toList();
        java.util.Map<Long, Long> childCounts = new java.util.HashMap<>();
        List<Object[]> rows = Category.getEntityManager()
                .createQuery("select parent.id, count(id) from Category where enabled = true and parent.id in ?1 group by parent.id", Object[].class)
                .setParameter(1, categoryIds)
                .getResultList();
        for (Object[] row : rows) {
            childCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return childCounts;
    }

    private CategoryDetailItem toCategoryDetailItem(Category category, String lang) {
        String name = resolveCategoryName(category, lang);
        String description = resolveCategoryDescription(category, lang);
        long childCount = Category.count("parent.id = ?1 and enabled = true", category.id);
        return new CategoryDetailItem(
                category.slug,
                name,
                description,
                safe(category.path),
                childCount,
                category.postCount,
                formatDate(category.createdAt)
        );
    }

    private List<CategoryBreadcrumb> buildBreadcrumbs(Category category, String lang) {
        List<CategoryBreadcrumb> items = new ArrayList<>();
        String path = safe(category.path);
        if (path.isBlank()) {
            items.add(new CategoryBreadcrumb(category.slug, resolveCategoryName(category, lang), "/category/" + category.slug));
            return items;
        }

        String[] slugs = path.split("\\.");
        for (String slug : slugs) {
            if (slug == null || slug.isBlank()) {
                continue;
            }
            Category node = Category.find("slug = ?1 and enabled = true", slug).firstResult();
            if (node != null) {
                items.add(new CategoryBreadcrumb(node.slug, resolveCategoryName(node, lang), "/category/" + node.slug));
            }
        }
        return items;
    }

    private boolean matchesKeyword(Category category, String keyword, String lang) {
        if (keyword.isBlank()) {
            return true;
        }

        String lower = keyword.toLowerCase(Locale.ROOT);
        String name = safe(LanguageHelper.resolveLocalizedValue(category.name, lang)).toLowerCase(Locale.ROOT);
        String description = safe(LanguageHelper.resolveLocalizedValue(category.description, lang)).toLowerCase(Locale.ROOT);
        String slug = safe(category.slug).toLowerCase(Locale.ROOT);
        String path = safe(category.path).toLowerCase(Locale.ROOT);
        return name.contains(lower) || description.contains(lower) || slug.contains(lower) || path.contains(lower);
    }

    private String resolveCategoryName(Category category, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(category.name, lang);
        return name == null || name.isBlank() ? category.slug : name;
    }

    private String resolveCategoryDescription(Category category, String lang) {
        String description = LanguageHelper.resolveLocalizedValue(category.description, lang);
        return description == null || description.isBlank()
                ? i18n.t("category.list_description") : description;
    }

    private String categoryDescription(Category category, String lang) {
        String description = LanguageHelper.resolveLocalizedValue(category.description, lang);
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return i18n.format("category.page_description", resolveCategoryName(category, lang));
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

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
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
