package com.biliwind.blog.edge;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.*;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Path("/search")
public class EdgeSearchController {

    private static final Logger log = Logger.getLogger(EdgeSearchController.class);
    private static final int PAGE_SIZE = 10;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Inject
    @Location("blog/search.html")
    Template search;

    @Inject
    @Location("blog/search.content.html")
    Template searchContent;

    @Inject
    LanguageContext languageContext;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("q") String keyword,
                                  @QueryParam("type") @DefaultValue("all") String type,
                                  @QueryParam("sort") @DefaultValue("") String sort,
                                  @QueryParam("date") @DefaultValue("") String date,
                                  @QueryParam("page") @DefaultValue("1") Integer page,
                                  @Context HttpHeaders httpHeaders) {
        int currentPage = page == null || page < 1 ? 1 : page;
        String searchKeyword = safe(keyword);
        String searchType = normalizeType(type);
        String searchSort = normalizeSort(sort);
        String searchDate = normalizeDate(date);
        String lang = languageContext.getLang();

        List<SearchHit> allHits = buildHits(searchKeyword, searchType, searchSort, searchDate, lang);
        boolean usedElasticsearch = false;
        boolean esDegraded = false;

        long totalCount = allHits.size();
        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        int fromIndex = Math.max(0, (currentPage - 1) * PAGE_SIZE);
        int toIndex = Math.min(allHits.size(), fromIndex + PAGE_SIZE);
        List<SearchHit> pageItems = allHits.subList(fromIndex, toIndex);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? searchContent : search;
        return template
                .data("language", lang)
                .data("pageTitle", buildPageTitle(searchKeyword))
                .data("searchKeyword", searchKeyword)
                .data("searchType", searchType)
                .data("searchSort", searchSort)
                .data("searchDate", searchDate)
                .data("hasKeyword", !searchKeyword.isBlank())
                .data("totalCount", totalCount)
                .data("currentPage", currentPage)
                .data("totalPages", totalPages)
                .data("hasPrevPage", currentPage > 1)
                .data("hasNextPage", currentPage < totalPages)
                .data("prevPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, Math.max(1, currentPage - 1)))
                .data("nextPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, Math.min(totalPages, currentPage + 1)))
                .data("typeLinks", buildTypeLinks(searchKeyword, searchType, searchSort, searchDate))
                .data("sortLinks", buildSortLinks(searchKeyword, searchType, searchSort, searchDate))
                .data("dateLinks", buildDateLinks(searchKeyword, searchType, searchSort, searchDate))
                .data("hits", pageItems)
                .data("usedElasticsearch", usedElasticsearch)
                .data("esDegraded", esDegraded);
    }

    private List<SearchHit> buildHits(String keyword, String type, String sort, String date, String lang) {
        if (keyword.isBlank()) {
            return List.of();
        }

        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        OffsetDateTime threshold = dateThreshold(date);
        List<SearchHit> hits = new ArrayList<>();

        if ("all".equals(type) || "post".equals(type)) {
            List<Post> posts = Post.find(
                    "status = ?1 and deletedAt is null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2)",
                    PostStatus.PUBLISHED, "%\"" + region + "\"%"
            ).list();
            posts.stream()
                    .filter(post -> matchesPost(post, lowerKeyword, lang))
                    .map(post -> toPostHit(post, lang))
                    .filter(hit -> threshold == null || hit.date == null || !hit.date.isBefore(threshold))
                    .forEach(hits::add);
        }

        if ("all".equals(type) || "tag".equals(type)) {
            List<Tag> tags = Tag.listAll();
            tags.stream()
                    .filter(tag -> matchesTag(tag, lowerKeyword, lang))
                    .map(tag -> toTagHit(tag, lang))
                    .filter(hit -> threshold == null || hit.date == null || !hit.date.isBefore(threshold))
                    .forEach(hits::add);
        }

        if ("all".equals(type) || "category".equals(type)) {
            List<Category> categories = Category.listAll();
            categories.stream()
                    .filter(category -> matchesCategory(category, lowerKeyword, lang))
                    .map(category -> toCategoryHit(category, lang))
                    .filter(hit -> threshold == null || hit.date == null || !hit.date.isBefore(threshold))
                    .forEach(hits::add);
        }

        hits.sort(hitComparator(sort));
        return hits;
    }


    private SearchHit toPostHit(Post post, String lang) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
        }

        String categoryName = "未分类";
        String categorySlug = "uncategorized";
        if (post.category != null) {
            categoryName = LanguageHelper.resolveLocalizedValue(post.category.name, lang);
            categorySlug = post.category.slug;
        }

        List<EdgeIndexController.TagItem> tags = new ArrayList<>();
        List<PostTag> postTags = PostTag.find("post", post).list();
        for (PostTag pt : postTags) {
            String tagName = LanguageHelper.resolveLocalizedValue(pt.tag.name, lang);
            tags.add(new EdgeIndexController.TagItem(tagName, pt.tag.slug));
        }

        OffsetDateTime displayDate = effectiveDate(post);
        return new SearchHit(
                "post",
                "Post",
                title,
                summary,
                "/post/" + post.slug,
                displayDate,
                formatDate(displayDate),
                "slug: " + post.slug,
                "[Read More]",
                categoryName,
                categorySlug,
                tags
        );
    }

    private SearchHit toTagHit(Tag tag, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(tag.name, lang);
        String description = LanguageHelper.resolveLocalizedValue(tag.description, lang);
        if (name == null || name.isBlank()) {
            name = tag.slug;
        }
        if (description == null || description.isBlank()) {
            description = "Tag archive.";
        }

        long postCount = PostTag.count("tag.id = ?1 and post.status = ?2 and post.deletedAt is null", tag.id, PostStatus.PUBLISHED);
        return new SearchHit(
                "tag",
                "Tag",
                name,
                description,
                "/tag/" + tag.slug,
                tag.createdAt,
                formatDate(tag.createdAt),
                "posts: " + postCount + " | slug: " + tag.slug,
                "[Browse Tag]",
                null,
                null,
                List.of()
        );
    }

    private SearchHit toCategoryHit(Category category, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(category.name, lang);
        String description = LanguageHelper.resolveLocalizedValue(category.description, lang);
        if (name == null || name.isBlank()) {
            name = category.slug;
        }
        if (description == null || description.isBlank()) {
            description = "Category archive.";
        }

        long childCount = Category.count("parent.id", category.id);
        return new SearchHit(
                "category",
                "Category",
                name,
                description,
                "/category/" + category.slug,
                category.createdAt,
                formatDate(category.createdAt),
                "children: " + childCount + " | path: " + safe(category.path),
                "[Browse Category]",
                null,
                null,
                List.of()
        );
    }

    private Comparator<SearchHit> hitComparator(String sort) {
        Comparator<SearchHit> byDateDesc = Comparator
                .comparing((SearchHit hit) -> hit.date, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();

        Comparator<SearchHit> byTypeWeight = Comparator.comparingInt(hit -> switch (hit.type) {
            case "post" -> 0;
            case "tag" -> 1;
            case "category" -> 2;
            default -> 3;
        });

        Comparator<SearchHit> byTitle = Comparator.comparing(hit -> hit.title.toLowerCase(Locale.ROOT));

        if ("latest".equals(sort) || "hot".equals(sort)) {
            return byDateDesc.thenComparing(byTypeWeight).thenComparing(byTitle);
        }
        return byTypeWeight.thenComparing(byDateDesc).thenComparing(byTitle);
    }

    private boolean matchesPost(Post post, String lowerKeyword, String lang) {
        String title = safe(LanguageHelper.resolveLocalizedValue(post.title, lang)).toLowerCase(Locale.ROOT);
        String summary = safe(LanguageHelper.resolveLocalizedValue(post.summary, lang)).toLowerCase(Locale.ROOT);
        String slug = safe(post.slug).toLowerCase(Locale.ROOT);
        return title.contains(lowerKeyword) || summary.contains(lowerKeyword) || slug.contains(lowerKeyword);
    }

    private boolean matchesTag(Tag tag, String lowerKeyword, String lang) {
        String name = safe(LanguageHelper.resolveLocalizedValue(tag.name, lang)).toLowerCase(Locale.ROOT);
        String description = safe(LanguageHelper.resolveLocalizedValue(tag.description, lang)).toLowerCase(Locale.ROOT);
        String slug = safe(tag.slug).toLowerCase(Locale.ROOT);
        return name.contains(lowerKeyword) || description.contains(lowerKeyword) || slug.contains(lowerKeyword);
    }

    private boolean matchesCategory(Category category, String lowerKeyword, String lang) {
        String name = safe(LanguageHelper.resolveLocalizedValue(category.name, lang)).toLowerCase(Locale.ROOT);
        String description = safe(LanguageHelper.resolveLocalizedValue(category.description, lang)).toLowerCase(Locale.ROOT);
        String slug = safe(category.slug).toLowerCase(Locale.ROOT);
        String path = safe(category.path).toLowerCase(Locale.ROOT);
        return name.contains(lowerKeyword) || description.contains(lowerKeyword) || slug.contains(lowerKeyword) || path.contains(lowerKeyword);
    }

    private OffsetDateTime effectiveDate(Post post) {
        return post.publishedAt != null ? post.publishedAt : post.createdAt;
    }

    private OffsetDateTime dateThreshold(String date) {
        OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        return switch (date) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "365d" -> now.minusDays(365);
            default -> null;
        };
    }

    private String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
    }

    private String buildPageTitle(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "Search";
        }
        return "Search - " + keyword;
    }

    private String buildSearchUrl(String keyword, String type, String sort, String date, Integer page) {
        StringBuilder url = new StringBuilder("/search?");
        url.append("q=").append(urlEncode(keyword));
        url.append("&type=").append(urlEncode(type));
        if (!sort.isBlank()) {
            url.append("&sort=").append(urlEncode(sort));
        }
        if (!date.isBlank()) {
            url.append("&date=").append(urlEncode(date));
        }
        if (page != null && page > 1) {
            url.append("&page=").append(page);
        }
        return url.toString();
    }

    private List<FilterLink> buildTypeLinks(String keyword, String currentType, String sort, String date) {
        return List.of(
                new FilterLink("All", buildSearchUrl(keyword, "all", sort, date, 1), cssClass("all".equals(currentType))),
                new FilterLink("Post", buildSearchUrl(keyword, "post", sort, date, 1), cssClass("post".equals(currentType))),
                new FilterLink("Tag", buildSearchUrl(keyword, "tag", sort, date, 1), cssClass("tag".equals(currentType))),
                new FilterLink("Category", buildSearchUrl(keyword, "category", sort, date, 1), cssClass("category".equals(currentType)))
        );
    }

    private List<FilterLink> buildSortLinks(String keyword, String type, String currentSort, String date) {
        return List.of(
                new FilterLink("Default", buildSearchUrl(keyword, type, "", date, 1), cssClass(currentSort.isBlank())),
                new FilterLink("Latest", buildSearchUrl(keyword, type, "latest", date, 1), cssClass("latest".equals(currentSort))),
                new FilterLink("Hot", buildSearchUrl(keyword, type, "hot", date, 1), cssClass("hot".equals(currentSort)))
        );
    }

    private List<FilterLink> buildDateLinks(String keyword, String type, String sort, String currentDate) {
        return List.of(
                new FilterLink("All", buildSearchUrl(keyword, type, sort, "", 1), cssClass(currentDate.isBlank())),
                new FilterLink("7 Days", buildSearchUrl(keyword, type, sort, "7d", 1), cssClass("7d".equals(currentDate))),
                new FilterLink("30 Days", buildSearchUrl(keyword, type, sort, "30d", 1), cssClass("30d".equals(currentDate))),
                new FilterLink("365 Days", buildSearchUrl(keyword, type, sort, "365d", 1), cssClass("365d".equals(currentDate)))
        );
    }

    private String cssClass(boolean active) {
        return active ? "text-green-500 font-bold" : "text-gray-400 hover:text-white";
    }

    private String normalizeType(String type) {
        if ("post".equals(type) || "tag".equals(type) || "category".equals(type)) {
            return type;
        }
        return "all";
    }

    private String normalizeSort(String sort) {
        if ("latest".equals(sort) || "hot".equals(sort)) {
            return sort;
        }
        return "";
    }

    private String normalizeDate(String date) {
        if ("7d".equals(date) || "30d".equals(date) || "365d".equals(date)) {
            return date;
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record SearchHit(
            String type,
            String typeLabel,
            String title,
            String summary,
            String url,
            OffsetDateTime date,
            String dateText,
            String meta,
            String actionLabel,
            String categoryName,
            String categorySlug,
            List<EdgeIndexController.TagItem> tags
    ) {
    }

    @io.quarkus.runtime.annotations.RegisterForReflection
    public record FilterLink(String label, String url, String cssClass) {
    }

}
