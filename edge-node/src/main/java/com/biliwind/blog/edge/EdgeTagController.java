package com.biliwind.blog.edge;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.Tag;
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
import java.util.List;
import java.util.Locale;

@Path("/tag")
public class EdgeTagController {

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

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance list(@QueryParam("q") String keyword,
                                 @QueryParam("page") @DefaultValue("1") Integer page,
                                 @Context HttpHeaders httpHeaders) {
        int currentPage = page == null || page < 1 ? 1 : page;
        String lang = languageContext.getLang();
        String safeKeyword = safe(keyword);

        List<Tag> allTags = Tag.listAll();
        List<TagListItem> filtered = allTags.stream()
                .filter(t -> matchesKeyword(t, safeKeyword, lang))
                .map(t -> toTagListItem(t, lang))
                .sorted((a, b) -> Long.compare(b.postCount, a.postCount))
                .toList();

        long totalCount = filtered.size();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        int fromIndex = Math.max(0, (currentPage - 1) * PAGE_SIZE);
        int toIndex = Math.min(filtered.size(), fromIndex + PAGE_SIZE);
        List<TagListItem> pageItems = filtered.subList(fromIndex, toIndex);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? tagContent : tag;
        return template
                .data("language", lang)
                .data("detailMode", false)
                .data("pageTitle", safeKeyword.isBlank() ? "Tags" : "Tags - " + safeKeyword)
                .data("navPath", "~/windblog / tags")
                .data("keyword", safeKeyword)
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildTagListUrl(safeKeyword, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildTagListUrl(safeKeyword, Math.min(totalPages, currentPage + 1)))
                .data("tags", pageItems);
    }

    @GET
    @Path("/{slug}")
    @Produces(MediaType.TEXT_HTML)
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

        var query = Post.find("status = ?1 and deletedAt is null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2) and id in "
                + "(select pt.post.id from PostTag pt where pt.tag.id = ?3) "
                + "order by publishedAt desc nulls last, createdAt desc", PostStatus.PUBLISHED, "%\"" + region + "\"%", entity.id);

        long totalCount = query.count();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<Post> posts = query.page(Page.of(currentPage - 1, PAGE_SIZE)).list();
        List<TagPostItem> postItems = posts.stream().map(post -> toTagPostItem(post, lang)).toList();

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? tagContent : tag;
        return template
                .data("language", lang)
                .data("detailMode", true)
                .data("pageTitle", "Tag - " + tagName)
                .data("navPath", "~/windblog / tag / " + entity.slug)
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildTagDetailUrl(entity.slug, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildTagDetailUrl(entity.slug, Math.min(totalPages, currentPage + 1)))
                .data("currentTag", new TagDetailItem(entity.slug, tagName, tagDescription, totalCount, formatDate(entity.createdAt)))
                .data("posts", postItems);
    }

    private TagListItem toTagListItem(Tag tag, String lang) {
        String name = resolveTagName(tag, lang);
        String description = resolveTagDescription(tag, lang);
        long postCount = PostTag.count("tag.id = ?1 and post.status = ?2 and post.deletedAt is null", tag.id, PostStatus.PUBLISHED);
        return new TagListItem(tag.slug, name, description, postCount, formatDate(tag.createdAt));
    }

    private TagPostItem toTagPostItem(Post post, String lang) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
        }

        OffsetDateTime date = post.publishedAt != null ? post.publishedAt : post.createdAt;

        List<TagItem> tags = PostTag.<PostTag>find("post", post).stream()
                .map(pt -> new TagItem(LanguageHelper.resolveLocalizedValue(pt.tag.name, lang), pt.tag.slug))
                .toList();

        return new TagPostItem(post.slug, title, summary, formatDate(date),
                post.category != null ? LanguageHelper.resolveLocalizedValue(post.category.name, lang) : "未分类", tags);
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

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record TagItem(String name, String slug) {
    }

    public record TagListItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    public record TagDetailItem(String slug, String name, String description, long postCount, String createdAtText) {
    }

    public record TagPostItem(String slug, String title, String summary, String publishedAtText, String categoryName,
                              List<TagItem> tags) {
    }
}