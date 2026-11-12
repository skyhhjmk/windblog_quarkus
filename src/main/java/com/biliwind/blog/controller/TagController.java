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
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.service.ConfigTemplateData;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import io.quarkus.panache.common.Page;
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
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@Path("/tag")
@io.smallrye.common.annotation.Blocking
public class TagController {

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
                .put("totalCount", totalCount)
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
        int currentPage = page == null || page < 1 ? 1 : page;
        String lang = languageContext.getLang();

        Tag entity = Tag.find("slug", normalizedSlug).firstResult();
        if (entity == null) {
            throw new NotFoundException("Tag not found: " + normalizedSlug);
        }

        String tagName = resolveTagName(entity, lang);
        String tagDescription = resolveTagDescription(entity, lang);

        RegionVisibility.PositionalFilter regionFilter =
                RegionVisibility.jpqlFilter(2, regionContext.getCurrentRegion());
        var query = Post.find("status = ?1 and deletedAt is null and visibility = 0 and publishedRevision is not null and "
                        + regionFilter.fragment() + " and id in "
                        + "(select pt.post.id from PostTag pt where pt.tag = ?3) order by publishedAt desc nulls last, createdAt desc",
                PostStatus.PUBLISHED, regionFilter.regionPattern(), entity);

        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        java.util.Map<Long, List<TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        java.util.Map<Long, PostCoverImageHelper.CoverImageInfo> featuredInfos = PostCoverImageHelper.findFeaturedImageInfos(
                posts.stream().map(post -> post.id).toList());
        List<TagPostItem> postItems = posts.stream()
                .map(post -> toTagPostItem(post, lang, tagsByPost.getOrDefault(post.id, List.of()),
                        featuredInfos.get(post.id)))
                .toList();

        boolean pjaxRequest = PjaxHelper.isPjaxRequest(httpHeaders);
        pageIdentityContext.set(pageIdentityService.page(
                "/tag/" + entity.slug + (currentPage > 1 ? "?page=" + currentPage : ""), lang));
        return pageRenderer.render(PageView.TAG, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("jsonLd", structuredDataService.collection(tagName, "/tag/" + entity.slug, lang,
                        List.of(new com.biliwind.blog.service.StructuredDataService.Breadcrumb(
                                        i18n.t("tag.title"), "/tag"),
                                new com.biliwind.blog.service.StructuredDataService.Breadcrumb(tagName, "/tag/" + entity.slug))))
                .put("pjaxRequest", pjaxRequest)
                .put("detailMode", true)
                .put("pageTitle", withPageSuffix(i18n.format("tag.detail_title", tagName), currentPage))
                .put("pageDescription", tagPageDescription(entity, lang))
                .put("navPath", ConfigTemplateData.siteNavRoot() + " / tag / " + entity.slug)
                .put("totalCount", totalCount)
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildTagDetailUrl(entity.slug, Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildTagDetailUrl(entity.slug, Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildTagDetailUrl(entity.slug, 1))
                .put("lastPageUrl", buildTagDetailUrl(entity.slug, totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildTagDetailUrl(entity.slug, pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("currentTag", new TagDetailItem(entity.slug, tagName, tagDescription, totalCount, formatDate(entity.createdAt)))
                .put("posts", postItems));
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

    private TagPostItem toTagPostItem(Post post, String lang, List<TagItem> tags,
                                      PostCoverImageHelper.CoverImageInfo featuredInfo) {
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
        PostCoverImageHelper.CoverImageInfo coverInfo =
                PostCoverImageHelper.resolveCoverImageInfo(post, lang, featuredInfo);
        return new TagPostItem(post.slug, title, summary, formatDate(date),
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", categories, tags,
                coverInfo.url(), coverInfo.thumbhash());
    }

    @TemplateData
    public record TagItem(String name, String slug) {}

    @TemplateData
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
        return description == null || description.isBlank()
                ? i18n.t("tag.list_description") : description;
    }

    private String tagPageDescription(Tag tag, String lang) {
        String description = LanguageHelper.resolveLocalizedValue(tag.description, lang);
        if (description != null && !description.isBlank()) {
            return description.trim();
        }
        return i18n.format("tag.page_description", resolveTagName(tag, lang));
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

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
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
