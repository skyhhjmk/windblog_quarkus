package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String keyword,
                                 @QueryParam("page") @DefaultValue("1") Integer page,
                                 @Context HttpHeaders httpHeaders) {
        int currentPage = page == null || page < 1 ? 1 : page;
        String lang = languageContext.getLang();
        String safeKeyword = safe(keyword);

        List<Category> allCategories = Category.listAll();
        List<CategoryListItem> filtered = allCategories.stream()
                .filter(c -> matchesKeyword(c, safeKeyword, lang))
                .map(c -> toCategoryListItem(c, lang))
                .sorted((a, b) -> a.path.compareToIgnoreCase(b.path))
                .toList();

        long totalCount = filtered.size();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        int fromIndex = Math.max(0, (currentPage - 1) * PAGE_SIZE);
        int toIndex = Math.min(filtered.size(), fromIndex + PAGE_SIZE);
        List<CategoryListItem> pageItems = filtered.subList(fromIndex, toIndex);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? categoryContent : category;
        return template
                .data("language", lang)
                .data("detailMode", false)
                .data("pageTitle", safeKeyword.isBlank() ? "Categories" : "Categories - " + safeKeyword)
                .data("navPath", "~/windblog / categories")
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
                                   @Context HttpHeaders httpHeaders) {
        String normalizedSlug = normalizeSlug(slug);
        String lang = languageContext.getLang();

        Category entity = Category.find("slug", normalizedSlug).firstResult();
        if (entity == null) {
            throw new NotFoundException("Category not found: " + normalizedSlug);
        }

        List<Category> children = Category.list("parent.id = ?1 order by createdAt desc", entity.id);
        List<CategoryListItem> childItems = children.stream().map(c -> toCategoryListItem(c, lang)).toList();

        var query = Post.find("category.id = ?1 and status = ?2 and deletedAt is null "
                + "order by publishedAt desc nulls last, createdAt desc", entity.id, PostStatus.PUBLISHED);
        List<Post> posts = query.list(); // For now, list all. We could add pagination later.
        List<CategoryPostItem> postItems = posts.stream().map(post -> toCategoryPostItem(post, lang)).toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? categoryContent : category;
        return template
                .data("language", lang)
                .data("detailMode", true)
                .data("pageTitle", "Category - " + resolveCategoryName(entity, lang))
                .data("navPath", "~/windblog / category / " + entity.slug)
                .data("totalCount", childItems.size())
                .data("postCount", postItems.size())
                .data("currentPage", 1)
                .data("totalPages", 1)
                .data("currentCategory", toCategoryDetailItem(entity, lang))
                .data("breadcrumbs", buildBreadcrumbs(entity, lang))
                .data("children", childItems)
                .data("posts", postItems);
    }

    private CategoryPostItem toCategoryPostItem(Post post, String lang) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "暂无摘要";
        }
        
        List<TagItem> tags = PostTag.<PostTag>find("post", post).stream()
                .map(pt -> new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, lang), pt.tag.slug))
                .toList();

        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;
        return new CategoryPostItem(post.slug, title, summary, formatDate(date), 
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", tags);
    }

    public record TagItem(String name, String slug) {}

    private CategoryListItem toCategoryListItem(Category category, String lang) {
        String name = resolveCategoryName(category, lang);
        String description = resolveCategoryDescription(category, lang);
        long childCount = Category.count("parent.id", category.id);
        return new CategoryListItem(
                category.slug,
                name,
                description,
                safe(category.path),
                childCount,
                formatDate(category.createdAt)
        );
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record CategoryListItem(String slug, String name, String description, String path, long childCount, String createdAtText) {
    }

    public record CategoryDetailItem(String slug, String name, String description, String path, long childCount, String createdAtText) {
    }

    public record CategoryBreadcrumb(String slug, String name, String url) {
    }

    public record CategoryPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<TagItem> tags) {
    }
}
