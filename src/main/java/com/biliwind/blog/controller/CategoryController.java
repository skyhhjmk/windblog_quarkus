package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.service.ConfigTemplateData;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
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

@Path("/category")
public class CategoryController {

    private static final int PAGE_SIZE = 12;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    @Location("blog/category.html")
    Template category;

    @Inject
    @Location("blog/category.content.html")
    Template categoryContent;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

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
            categoryQuery = Category.find("order by path");
        } else {
            String pattern = "%" + safeKeyword.toLowerCase(Locale.ROOT) + "%";
            categoryQuery = Category.find(
                    "lower(cast(name as String)) like ?1 or lower(cast(description as String)) like ?1 "
                            + "or lower(slug) like ?1 or lower(path) like ?1 order by path",
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

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? categoryContent : category;
        return template
                .data("language", lang)
                .data("detailMode", false)
                .data("pageTitle", safeKeyword.isBlank() ? "Categories" : "Categories - " + safeKeyword)
                .data("navPath", ConfigTemplateData.siteNavRoot() + " / categories")
                .data("keyword", safeKeyword)
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildCategoryListUrl(safeKeyword, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildCategoryListUrl(safeKeyword, Math.min(totalPages, currentPage + 1)))
                .data("categories", pageItems);
    }

    @GET
    @Path("/{slug}")
    @Produces(MediaType.TEXT_HTML)
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

        List<Category> children = Category.list("parent.id = ?1 order by createdAt desc", entity.id);
        List<CategoryListItem> childItems = children.stream().map(c -> toCategoryListItem(c, lang)).toList();

        String currentRegion = regionContext.getCurrentRegion().getCode();
        String queryStr = "category = ?1 and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null "
                + "and (visibilityRegions is null or cast(visibilityRegions as String) like ?3) "
                + "order by publishedAt desc nulls last, createdAt desc";
        PanacheQuery<Post> query = Post.find(queryStr, entity, PostStatus.PUBLISHED, "%\"" + currentRegion + "\"%");
        long postCount = query.count();
        int totalPages = postCount == 0 ? 1 : (int) Math.ceil((double) postCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }
        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, List<TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        List<CategoryPostItem> postItems = posts.stream()
                .map(post -> toCategoryPostItem(post, lang, tagsByPost.getOrDefault(post.id, List.of())))
                .toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? categoryContent : category;
        return template
                .data("language", lang)
                .data("detailMode", true)
                .data("pageTitle", "Category - " + resolveCategoryName(entity, lang))
                .data("navPath", ConfigTemplateData.siteNavRoot() + " / category / " + entity.slug)
                .data("totalCount", childItems.size())
                .data("postCount", postCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildCategoryDetailUrl(entity.slug, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildCategoryDetailUrl(entity.slug, Math.min(totalPages, currentPage + 1)))
                .data("currentCategory", toCategoryDetailItem(entity, lang))
                .data("breadcrumbs", buildBreadcrumbs(entity, lang))
                .data("children", childItems)
                .data("posts", postItems);
    }

    private CategoryPostItem toCategoryPostItem(Post post, String lang, List<TagItem> tags) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "暂无摘要";
        }
        
        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;
        return new CategoryPostItem(post.slug, title, summary, formatDate(date), 
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", tags);
    }

    public record TagItem(String name, String slug) {}

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
                .createQuery("select parent.id, count(id) from Category where parent.id in ?1 group by parent.id", Object[].class)
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
        long childCount = Category.count("parent.id", category.id);
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
            Category node = Category.find("slug", slug).firstResult();
            if (node != null) {
                items.add(new CategoryBreadcrumb(node.slug, resolveCategoryName(node, lang), "/category/" + node.slug));
            } else {
                items.add(new CategoryBreadcrumb(slug, slug, "/category/" + slug));
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
        return description == null || description.isBlank() ? "Category archive." : description;
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
        return url.toString();
    }

    private String buildCategoryDetailUrl(String slug, int page) {
        String url = "/category/" + urlEncode(slug);
        return page > 1 ? url + "?page=" + page : url;
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

    public record CategoryListItem(String slug, String name, String description, String path, long childCount, long postCount, String createdAtText) {
    }

    public record CategoryDetailItem(String slug, String name, String description, String path, long childCount, long postCount, String createdAtText) {
    }

    public record CategoryBreadcrumb(String slug, String name, String url) {
    }

    public record CategoryPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<TagItem> tags) {
    }
}
