package com.biliwind.blog.controller;

import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.service.ConfigTemplateData;
import io.quarkus.panache.common.Page;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Path("/tag")
public class TagController {

    private static final int PAGE_SIZE = 12;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    @Location("blog/tag.html")
    Template tag;

    @Inject
    @Location("blog/tag.content.html")
    Template tagContent;

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

        io.quarkus.hibernate.orm.panache.PanacheQuery<Tag> tagQuery;
        if (safeKeyword.isBlank()) {
            tagQuery = Tag.find("order by id");
        } else {
            String pattern = "%" + safeKeyword.toLowerCase(Locale.ROOT) + "%";
            tagQuery = Tag.find(
                    "lower(cast(name as String)) like ?1 or lower(cast(description as String)) like ?1 "
                            + "or lower(slug) like ?1 order by id",
                    pattern);
        }

        long totalCount = tagQuery.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<Tag> tags = tagQuery.page(io.quarkus.panache.common.Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, Long> postCounts = loadPostCounts(tags);
        List<TagListItem> pageItems = tags.stream()
                .map(tag -> toTagListItem(tag, lang, postCounts.getOrDefault(tag.id, 0L)))
                .sorted((a, b) -> Long.compare(b.postCount, a.postCount))
                .toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? tagContent : tag;
        return template
                .data("language", lang)
                .data("detailMode", false)
                .data("pageTitle", safeKeyword.isBlank() ? "Tags" : "Tags - " + safeKeyword)
                .data("navPath", ConfigTemplateData.siteNavRoot() + " / tags")
                .data("keyword", safeKeyword)
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildTagListUrl(safeKeyword, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildTagListUrl(safeKeyword, Math.min(totalPages, currentPage + 1)))
                .data("firstPageUrl", buildTagListUrl(safeKeyword, 1))
                .data("lastPageUrl", buildTagListUrl(safeKeyword, totalPages))
                .data("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildTagListUrl(safeKeyword, pageNumber)))
                .data("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .data("tags", pageItems);
    }

    @GET
    @Path("/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance detail(@PathParam("slug") String slug,
                                   @QueryParam("page") @DefaultValue("1") Integer page,
                                   @Context HttpHeaders httpHeaders) {
        String normalizedSlug = normalizeSlug(slug);
        int currentPage = page == null || page < 1 ? 1 : page;
        String lang = languageContext.getLang();

        Tag entity = Tag.find("slug", normalizedSlug).firstResult();
        if (entity == null) {
            throw new NotFoundException("Tag not found: " + normalizedSlug);
        }

        String tagName = resolveTagName(entity, lang);
        String tagDescription = resolveTagDescription(entity, lang);

        String currentRegion = regionContext.getCurrentRegion().getCode();
        var query = Post.find("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2) and id in "
                        + "(select pt.post.id from PostTag pt where pt.tag = ?3) order by publishedAt desc nulls last, createdAt desc",
                PostStatus.PUBLISHED, "%\"" + currentRegion + "\"%", entity);

        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, List<TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        List<TagPostItem> postItems = posts.stream()
                .map(post -> toTagPostItem(post, lang, tagsByPost.getOrDefault(post.id, List.of())))
                .toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? tagContent : tag;
        return template
                .data("language", lang)
                .data("detailMode", true)
                .data("pageTitle", "Tag - " + tagName)
                .data("navPath", ConfigTemplateData.siteNavRoot() + " / tag / " + entity.slug)
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildTagDetailUrl(entity.slug, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildTagDetailUrl(entity.slug, Math.min(totalPages, currentPage + 1)))
                .data("firstPageUrl", buildTagDetailUrl(entity.slug, 1))
                .data("lastPageUrl", buildTagDetailUrl(entity.slug, totalPages))
                .data("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildTagDetailUrl(entity.slug, pageNumber)))
                .data("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .data("currentTag", new TagDetailItem(entity.slug, tagName, tagDescription, totalCount, formatDate(entity.createdAt)))
                .data("posts", postItems);
    }

    private TagListItem toTagListItem(Tag tag, String lang) {
        return toTagListItem(tag, lang, PostTag.count(
                "tag.id = ?1 and post.status = ?2 and post.deletedAt is null and post.visibility = 0 and post.publishedRevision is not null",
                tag.id, PostStatus.PUBLISHED));
    }

    private TagListItem toTagListItem(Tag tag, String lang, long postCount) {
        String name = resolveTagName(tag, lang);
        String description = resolveTagDescription(tag, lang);
        return new TagListItem(tag.slug, name, description, postCount, formatDate(tag.createdAt));
    }

    private java.util.Map<Long, Long> loadPostCounts(List<Tag> tags) {
        if (tags.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> tagIds = tags.stream().map(tag -> tag.id).toList();
        java.util.Map<Long, Long> postCounts = new java.util.HashMap<>();
        List<Object[]> rows = PostTag.getEntityManager().createQuery(
                        "select tag.id, count(post.id) from PostTag "
                                + "where tag.id in ?1 and post.status = ?2 and post.deletedAt is null "
                                + "and post.visibility = 0 and post.publishedRevision is not null group by tag.id",
                        Object[].class)
                .setParameter(1, tagIds)
                .setParameter(2, PostStatus.PUBLISHED)
                .getResultList();
        for (Object[] row : rows) {
            postCounts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return postCounts;
    }

    private TagPostItem toTagPostItem(Post post, String lang, List<TagItem> tags) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
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
        return new TagPostItem(post.slug, title, summary, formatDate(date), 
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", categories, tags);
    }

    public record TagItem(String name, String slug) {}

    public record CategoryItem(String name, String slug) {}

    private java.util.Map<Long, List<TagItem>> loadTagsByPost(List<Post> posts, String lang) {
        if (posts.isEmpty()) {
            return java.util.Map.of();
        }
        List<Long> postIds = posts.stream().map(post -> post.id).toList();
        java.util.Map<Long, List<TagItem>> tagsByPost = new java.util.HashMap<>();
        List<PostTag> postTags = PostTag.find("post.id in ?1", postIds).list();
        for (PostTag postTag : postTags) {
            tagsByPost.computeIfAbsent(postTag.post.id, ignored -> new java.util.ArrayList<>())
                    .add(new TagItem(LanguageHelper.resolveLocalizedValue(postTag.tag.name, lang), postTag.tag.slug));
        }
        return tagsByPost;
    }

    private boolean matchesKeyword(Tag tag, String keyword, String lang) {
        if (keyword.isBlank()) {
            return true;
        }

        String lower = keyword.toLowerCase(Locale.ROOT);
        String name = safe(LanguageHelper.resolveLocalizedValue(tag.name, lang)).toLowerCase(Locale.ROOT);
        String description = safe(LanguageHelper.resolveLocalizedValue(tag.description, lang)).toLowerCase(Locale.ROOT);
        String slug = safe(tag.slug).toLowerCase(Locale.ROOT);
        return name.contains(lower) || description.contains(lower) || slug.contains(lower);
    }

    private String resolveTagName(Tag tag, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(tag.name, lang);
        return name == null || name.isBlank() ? tag.slug : name;
    }

    private String resolveTagDescription(Tag tag, String lang) {
        String description = LanguageHelper.resolveLocalizedValue(tag.description, lang);
        return description == null || description.isBlank() ? "Tag archive." : description;
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
        return url.toString();
    }

    private String buildTagDetailUrl(String slug, int page) {
        StringBuilder url = new StringBuilder("/tag/").append(urlEncode(slug));
        if (page > 1) {
            url.append("?page=").append(page);
        }
        return url.toString();
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

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record TagListItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    public record TagDetailItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    public record TagPostItem(String slug, String title, String summary, String publishedAtText, String categoryName, List<CategoryItem> categories, List<TagItem> tags) {
    }
}
