package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.*;
import com.biliwind.blog.service.edge.EdgePersistentChannelClient;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.RoutedHttpExchange;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import com.biliwind.blog.service.SecurityMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Path("/search")
public class SearchController {

    private static final Logger log = Logger.getLogger(SearchController.class);
    private static final int PAGE_SIZE = 10;
    private static final int MAX_FALLBACK_PAGE = 1000;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    @Location("blog/search.html")
    Template search;

    @Inject
    @Location("blog/search.content.html")
    Template searchContent;

    @Inject
    LanguageContext languageContext;

    @Inject
    ElasticsearchPostSearchService postSearchService;

    @Inject
    SecurityMetricsService securityMetricsService;
    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    EdgePersistentChannelClient edgePersistentChannelClient;

    @PersistenceContext
    EntityManager entityManager;

    @Inject
    com.biliwind.blog.context.RegionContext regionContext;

    @QueryParam("use-es")
    @DefaultValue("true")
    boolean useElasticsearch;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@QueryParam("q") String keyword,
                                  @QueryParam("type") @DefaultValue("all") String type,
                                  @QueryParam("sort") @DefaultValue("") String sort,
                                  @QueryParam("date") @DefaultValue("") String date,
                                  @QueryParam("deep") @DefaultValue("false") boolean deep,
                                  @QueryParam("page") @DefaultValue("1") Integer page,
                                  @Context HttpHeaders httpHeaders) {
        int currentPage = page == null || page < 1 ? 1 : Math.min(MAX_FALLBACK_PAGE, page);
        String searchKeyword = safe(keyword);
        String searchType = normalizeType(type);
        String searchSort = normalizeSort(sort);
        String searchDate = normalizeDate(date);
        String lang = languageContext.getLang();

        List<SearchHit> searchHits;
        long totalCount;
        boolean searchResultAlreadyPaged = false;
        boolean usedElasticsearch = false;
        boolean esDegraded = false;
        boolean edgeNode = nodeRoleService.isEdgeNode();
        boolean deepSearchRequested = deep && edgeNode;
        boolean deepSearchFailed = false;

        if (deepSearchRequested && !searchKeyword.isBlank() && canUseDeepSearch(searchType)) {
            ElasticsearchResult primaryResult = searchPrimaryWithElasticsearch(searchKeyword, searchType, searchSort, searchDate, lang, currentPage);
            searchHits = primaryResult.hits();
            totalCount = primaryResult.totalCount();
            usedElasticsearch = primaryResult.usedElasticsearch();
            esDegraded = primaryResult.degraded();
            deepSearchFailed = primaryResult.degraded();
            searchResultAlreadyPaged = primaryResult.alreadyPaged();
        } else if (!edgeNode && useElasticsearch && !searchKeyword.isBlank() && ("all".equals(searchType) || "post".equals(searchType))) {
            ElasticsearchResult esResult = searchWithElasticsearch(searchKeyword, searchType, searchSort, searchDate, lang, currentPage);
            searchHits = esResult.hits();
            totalCount = esResult.totalCount();
            usedElasticsearch = esResult.usedElasticsearch();
            esDegraded = esResult.degraded();
            searchResultAlreadyPaged = esResult.alreadyPaged();
        } else {
            ElasticsearchResult databaseResult = searchWithDatabaseFallback(
                    searchKeyword, searchType, searchSort, searchDate, lang, currentPage);
            searchHits = databaseResult.hits();
            totalCount = databaseResult.totalCount();
            searchResultAlreadyPaged = databaseResult.alreadyPaged();
            esDegraded = databaseResult.degraded();
        }

        int totalPages = totalCount == 0 ? 1 : (int) Math.ceil((double) totalCount / PAGE_SIZE);
        if (currentPage > totalPages) {
            currentPage = totalPages;
        }

        List<SearchHit> pageItems;
        if (searchResultAlreadyPaged) {
            pageItems = searchHits;
        } else {
            int fromIndex = Math.max(0, (currentPage - 1) * PAGE_SIZE);
            int toIndex = Math.min(searchHits.size(), fromIndex + PAGE_SIZE);
            pageItems = searchHits.subList(fromIndex, toIndex);
        }

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
                .data("esDegraded", esDegraded)
                .data("edgeNode", edgeNode)
                .data("deepSearchRequested", deepSearchRequested)
                .data("deepSearchFailed", deepSearchFailed)
                .data("deepSearchUrl", buildDeepSearchUrl(searchKeyword, searchType, searchSort, searchDate));
    }

    @GET
    @Path("/suggest")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> suggest(@QueryParam("q") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        if (nodeRoleService.isEdgeNode()) {
            return buildLocalSuggestions(keyword);
        }
        try {
            return postSearchService.suggestPosts(keyword);
        } catch (Exception e) {
            log.error("获取搜索建议失败", e);
            return List.of();
        }
    }

    @GET
    @Path("/internal/deep")
    @Produces(MediaType.APPLICATION_JSON)
    public DeepSearchResponse deepSearch(@QueryParam("q") String keyword,
                                         @QueryParam("type") @DefaultValue("all") String type,
                                         @QueryParam("sort") @DefaultValue("") String sort,
                                         @QueryParam("date") @DefaultValue("") String date,
                                         @QueryParam("page") @DefaultValue("1") Integer page,
                                         @QueryParam("lang") @DefaultValue("zh-cn") String lang,
                                         @HeaderParam("X-WindBlog-Routed-From-Edge") String routedFromEdge) {
        if (!nodeRoleService.isPrimaryNode()) {
            throw new NotFoundException();
        }
        if (!"true".equalsIgnoreCase(routedFromEdge)) {
            throw new NotFoundException();
        }

        int currentPage = page == null || page < 1 ? 1 : Math.min(MAX_FALLBACK_PAGE, page);
        String searchKeyword = safe(keyword);
        String searchType = normalizeType(type);
        String searchSort = normalizeSort(sort);
        String searchDate = normalizeDate(date);
        ElasticsearchResult result = searchWithElasticsearch(searchKeyword, searchType, searchSort, searchDate, lang, currentPage);
        return new DeepSearchResponse(
                result.hits(),
                result.totalCount(),
                result.usedElasticsearch(),
                result.degraded()
        );
    }

    /**
     * Elasticsearch 故障时，文章搜索走 PostgreSQL tsvector + GIN 分页；标签和分类保持现有小表 fallback。
     */
    private ElasticsearchResult searchWithDatabaseFallback(
            String keyword, String type, String sort, String date, String lang, int page) {
        securityMetricsService.increment("search.degraded", "database_fallback");
        if (keyword == null || keyword.isBlank()) {
            return new ElasticsearchResult(List.of(), 0, false, true, true);
        }
        if ("all".equals(type)) {
            return searchAllWithDatabaseFallback(keyword, sort, date, lang, page);
        }
        if ("tag".equals(type) || "category".equals(type)) {
            return searchTaxonomyWithDatabaseFallback(keyword, type, date, lang, page);
        }
        if (!"post".equals(type)) {
            return new ElasticsearchResult(List.of(), 0, false, true, true);
        }

        String orderBy = "latest".equals(sort) || sort.isBlank()
                ? "p.published_at DESC NULLS LAST, p.id DESC"
                : "p.view_count DESC NULLS LAST, p.published_at DESC NULLS LAST, p.id DESC";
        String regionPattern = "%\"" + regionContext.getCurrentRegion().getCode() + "\"%";
        String searchPattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        OffsetDateTime threshold = dateThreshold(date);

        String where = "p.status = 1 and p.deleted_at is null and p.visibility = 0 "
                + "and p.published_revision_id is not null "
                + "and (p.visibility_regions is null or p.visibility_regions::text like ?3) "
                + "and (p.search_document @@ plainto_tsquery('simple', ?1) "
                + "or lower(p.slug) like ?2 escape '\\' "
                + "or lower(p.title::text) like ?2 escape '\\' "
                + "or lower(coalesce(p.summary::text, '')) like ?2 escape '\\')";
        if (threshold != null) {
            where = where + " and p.published_at >= ?4";
        }

        String selectSql = "select p.* from posts p where " + where + " order by " + orderBy;
        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(selectSql, Post.class);
        selectQuery.setParameter(1, keyword);
        selectQuery.setParameter(2, searchPattern);
        selectQuery.setParameter(3, regionPattern);
        if (threshold != null) {
            selectQuery.setParameter(4, threshold);
        }
        int safePage = Math.max(1, page);
        selectQuery.setFirstResult((safePage - 1) * PAGE_SIZE);
        selectQuery.setMaxResults(PAGE_SIZE);

        @SuppressWarnings("unchecked")
        List<Post> posts = selectQuery.getResultList();
        List<SearchHit> hits = new ArrayList<>();
        Map<Long, List<IndexController.TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        for (Post post : posts) {
            hits.add(toPostHit(post, lang, tagsByPost));
        }

        String countSql = "select count(*) from posts p where " + where;
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(countSql);
        countQuery.setParameter(1, keyword);
        countQuery.setParameter(2, searchPattern);
        countQuery.setParameter(3, regionPattern);
        if (threshold != null) {
            countQuery.setParameter(4, threshold);
        }
        Number total = (Number) countQuery.getSingleResult();
        return new ElasticsearchResult(hits, total.longValue(), false, true, true);
    }

    private ElasticsearchResult searchTaxonomyWithDatabaseFallback(
            String keyword, String type, String date, String lang, int page) {
        String table = "tag".equals(type) ? "tags" : "categories";
        String searchPattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        OffsetDateTime threshold = dateThreshold(date);
        String where = "(lower(" + table + ".slug) like ?1 escape '\\' "
                + "or lower(" + table + ".name::text) like ?1 escape '\\' "
                + "or lower(coalesce(" + table + ".description::text, '')) like ?1 escape '\\')";
        if (threshold != null) {
            where = where + " and " + table + ".created_at >= ?2";
        }

        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(
                "select count(*) from " + table + " where " + where);
        countQuery.setParameter(1, searchPattern);
        if (threshold != null) {
            countQuery.setParameter(2, threshold);
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();

        jakarta.persistence.Query selectQuery;
        if ("tag".equals(type)) {
            selectQuery = entityManager.createNativeQuery(
                    "select * from tags where " + where + " order by created_at desc, id desc", Tag.class);
        } else {
            selectQuery = entityManager.createNativeQuery(
                    "select * from categories where " + where + " order by created_at desc, id desc", Category.class);
        }
        selectQuery.setParameter(1, searchPattern);
        if (threshold != null) {
            selectQuery.setParameter(2, threshold);
        }
        int safePage = Math.max(1, page);
        selectQuery.setFirstResult((safePage - 1) * PAGE_SIZE);
        selectQuery.setMaxResults(PAGE_SIZE);

        List<SearchHit> hits = new ArrayList<>();
        if ("tag".equals(type)) {
            @SuppressWarnings("unchecked")
            List<Tag> tags = selectQuery.getResultList();
            for (Tag tag : tags) {
                hits.add(toTagHit(tag, lang));
            }
        } else {
            @SuppressWarnings("unchecked")
            List<Category> categories = selectQuery.getResultList();
            for (Category category : categories) {
                hits.add(toCategoryHit(category, lang));
            }
        }
        return new ElasticsearchResult(hits, total, false, true, true);
    }

    private ElasticsearchResult searchAllWithDatabaseFallback(
            String keyword, String sort, String date, String lang, int page) {
        OffsetDateTime threshold = dateThreshold(date);
        String searchPattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        int safePage = Math.min(MAX_FALLBACK_PAGE, Math.max(1, page));
        // Each source is bounded and paged in PostgreSQL; never materialize the full table.
        int candidateLimit = safePage * PAGE_SIZE;

        PostFallbackPage posts = findPostFallbackCandidates(keyword, searchPattern, threshold, candidateLimit);
        TaxonomyFallbackPage tags = findTaxonomyFallbackCandidates("tags", searchPattern, threshold, candidateLimit);
        TaxonomyFallbackPage categories = findTaxonomyFallbackCandidates("categories", searchPattern, threshold, candidateLimit);

        List<SearchHit> candidates = new ArrayList<>();
        Map<Long, List<IndexController.TagItem>> tagsByPost = loadTagsByPost(posts.items(), lang);
        for (Post post : posts.items()) {
            candidates.add(toPostHit(post, lang, tagsByPost));
        }
        for (Tag tag : tags.tags()) {
            candidates.add(toTagHit(tag, lang));
        }
        for (Category category : categories.categories()) {
            candidates.add(toCategoryHit(category, lang));
        }
        candidates.sort(hitComparator(sort));
        int fromIndex = Math.min(candidates.size(), (safePage - 1) * PAGE_SIZE);
        int toIndex = Math.min(candidates.size(), fromIndex + PAGE_SIZE);
        List<SearchHit> hits = new ArrayList<>(candidates.subList(fromIndex, toIndex));
        long total = posts.total() + tags.total() + categories.total();
        return new ElasticsearchResult(hits, total, false, true, true);
    }

    private PostFallbackPage findPostFallbackCandidates(
            String keyword, String searchPattern, OffsetDateTime threshold, int limit) {
        String orderBy = "p.published_at DESC NULLS LAST, p.id DESC";
        String regionPattern = "%\"" + regionContext.getCurrentRegion().getCode() + "\"%";
        String where = "p.status = 1 and p.deleted_at is null and p.visibility = 0 "
                + "and p.published_revision_id is not null "
                + "and (p.visibility_regions is null or p.visibility_regions::text like ?3) "
                + "and (p.search_document @@ plainto_tsquery('simple', ?1) "
                + "or lower(p.slug) like ?2 escape '\\' "
                + "or lower(p.title::text) like ?2 escape '\\' "
                + "or lower(coalesce(p.summary::text, '')) like ?2 escape '\\')";
        if (threshold != null) {
            where = where + " and p.published_at >= ?4";
        }
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery("select count(*) from posts p where " + where);
        setPostFallbackParameters(countQuery, keyword, searchPattern, regionPattern, threshold);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(
                "select p.* from posts p where " + where + " order by " + orderBy, Post.class);
        setPostFallbackParameters(selectQuery, keyword, searchPattern, regionPattern, threshold);
        selectQuery.setMaxResults(limit);
        @SuppressWarnings("unchecked")
        List<Post> items = selectQuery.getResultList();
        return new PostFallbackPage(items, total);
    }

    private TaxonomyFallbackPage findTaxonomyFallbackCandidates(
            String table, String searchPattern, OffsetDateTime threshold, int limit) {
        String where = "(lower(" + table + ".slug) like ?1 escape '\\' "
                + "or lower(" + table + ".name::text) like ?1 escape '\\' "
                + "or lower(coalesce(" + table + ".description::text, '')) like ?1 escape '\\')";
        if (threshold != null) {
            where = where + " and " + table + ".created_at >= ?2";
        }
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(
                "select count(*) from " + table + " where " + where);
        countQuery.setParameter(1, searchPattern);
        if (threshold != null) {
            countQuery.setParameter(2, threshold);
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();
        jakarta.persistence.Query selectQuery;
        if ("tags".equals(table)) {
            selectQuery = entityManager.createNativeQuery(
                    "select * from tags where " + where + " order by created_at desc, id desc", Tag.class);
        } else {
            selectQuery = entityManager.createNativeQuery(
                    "select * from categories where " + where + " order by created_at desc, id desc", Category.class);
        }
        selectQuery.setParameter(1, searchPattern);
        if (threshold != null) {
            selectQuery.setParameter(2, threshold);
        }
        selectQuery.setMaxResults(limit);
        if ("tags".equals(table)) {
            @SuppressWarnings("unchecked")
            List<Tag> tags = selectQuery.getResultList();
            return new TaxonomyFallbackPage(tags, List.of(), total);
        }
        @SuppressWarnings("unchecked")
        List<Category> categories = selectQuery.getResultList();
        return new TaxonomyFallbackPage(List.of(), categories, total);
    }

    private void setPostFallbackParameters(jakarta.persistence.Query query, String keyword,
                                            String searchPattern, String regionPattern,
                                            OffsetDateTime threshold) {
        query.setParameter(1, keyword);
        query.setParameter(2, searchPattern);
        query.setParameter(3, regionPattern);
        if (threshold != null) {
            query.setParameter(4, threshold);
        }
    }

    private record PostFallbackPage(List<Post> items, long total) {
    }

    private record TaxonomyFallbackPage(List<Tag> tags, List<Category> categories, long total) {
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private ElasticsearchResult searchPrimaryWithElasticsearch(String keyword, String type, String sort, String date, String lang, int page) {
        try {
            String query = buildDeepSearchQuery(keyword, type, sort, date, page, lang);
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", MediaType.APPLICATION_JSON);

            RoutedHttpExchange.Request request = new RoutedHttpExchange.Request(
                    "GET",
                    "/search/internal/deep",
                    query,
                    headers,
                    new byte[0]
            );

            RoutedHttpExchange.Response response = edgePersistentChannelClient.forwardWriteRequest(request);
            if (response.status() != 200) {
                log.warnf("深度搜索回源失败，状态码: %d", response.status());
                return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
            }
            return parseDeepSearchResponse(response.body(), keyword, type, sort, date, lang, page);
        } catch (Exception exception) {
            log.error("深度搜索回源失败，使用边缘本地数据库搜索", exception);
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        }
    }

    private ElasticsearchResult parseDeepSearchResponse(byte[] responseBody,
                                                        String keyword,
                                                        String type,
                                                        String sort,
                                                        String date,
                                                        String lang,
                                                        int page) throws Exception {
        if (responseBody == null || responseBody.length == 0) {
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        }

        JsonNode rootNode = OBJECT_MAPPER.readTree(responseBody);
        List<SearchHit> hits = new ArrayList<>();
        JsonNode hitNodes = rootNode.path("hits");
        if (hitNodes.isArray()) {
            for (JsonNode hitNode : hitNodes) {
                hits.add(parseSearchHit(hitNode));
            }
        }

        boolean usedElasticsearch = rootNode.path("usedElasticsearch").asBoolean(false);
        boolean degraded = rootNode.path("degraded").asBoolean(false);
        long totalCount = rootNode.path("totalCount").asLong(hits.size());
        return new ElasticsearchResult(hits, totalCount, usedElasticsearch, degraded, usedElasticsearch);
    }

    private SearchHit parseSearchHit(JsonNode hitNode) {
        OffsetDateTime date = null;
        String dateText = hitNode.path("dateText").asText("unknown");
        String dateValue = hitNode.path("date").asText("");
        if (!dateValue.isBlank()) {
            try {
                date = OffsetDateTime.parse(dateValue);
            } catch (Exception exception) {
                date = null;
            }
        }

        List<IndexController.TagItem> tags = new ArrayList<>();
        JsonNode tagNodes = hitNode.path("tags");
        if (tagNodes.isArray()) {
            for (JsonNode tagNode : tagNodes) {
                String tagName = tagNode.path("name").asText("");
                String tagSlug = tagNode.path("slug").asText("");
                tags.add(new IndexController.TagItem(tagName, tagSlug));
            }
        }

        return new SearchHit(
                hitNode.path("type").asText("post"),
                hitNode.path("typeLabel").asText("Post"),
                hitNode.path("title").asText(""),
                hitNode.path("summary").asText(""),
                hitNode.path("aiSummary").asText(""),
                hitNode.path("url").asText(""),
                hitNode.path("slug").asText(""),
                date,
                dateText,
                hitNode.path("meta").asText(""),
                hitNode.path("actionLabel").asText("[Read More]"),
                textOrNull(hitNode.path("categoryName")),
                textOrNull(hitNode.path("categorySlug")),
                hitNode.path("viewCount").asLong(0),
                tags
        );
    }

    private List<String> buildLocalSuggestions(String keyword) {
        String searchKeyword = safe(keyword);
        if (searchKeyword.isBlank()) {
            return List.of();
        }

        String lang = languageContext.getLang();
        ElasticsearchResult fallback = searchWithDatabaseFallback(
                searchKeyword, "all", "", "", lang, 1);
        List<SearchHit> hits = fallback.hits();
        List<String> suggestions = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (suggestions.size() >= 10) {
                break;
            }
            if (hit.title == null || hit.title.isBlank()) {
                continue;
            }
            if (suggestions.contains(hit.title)) {
                continue;
            }
            suggestions.add(hit.title);
        }
        return suggestions;
    }

    /**
     * 使用 Elasticsearch 搜索文章，失败时回退到数据库搜索
     */
    private ElasticsearchResult searchWithElasticsearch(String keyword, String type, String sort, String date, String lang, int page) {
        try {
            if (!postSearchService.isAvailable()) {
                log.debug("Elasticsearch 不可用，使用数据库搜索");
                return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
            }

            log.infof("使用 Elasticsearch 搜索：keyword=%s, type=%s, page=%d", keyword, type, page);

            ElasticsearchPostSearchService.SearchResult searchResult = postSearchService.searchPosts(
                    keyword,
                    page,
                    PAGE_SIZE,
                    "PUBLISHED",
                    null,
                    null,
                    regionContext.getCurrentRegion().getCode()
            );

            OffsetDateTime threshold = dateThreshold(date);
            List<SearchHit> hits = new ArrayList<>();

            for (ElasticsearchPostSearchService.SearchedPost post : searchResult.posts()) {
                if (threshold != null && post.publishedAt() != null) {
                    try {
                        OffsetDateTime postDate = OffsetDateTime.parse(post.publishedAt());
                        if (postDate.isBefore(threshold)) {
                            continue;
                        }
                    } catch (Exception e) {
                        log.debugf("解析日期失败：%s", post.publishedAt());
                    }
                }

                SearchHit hit = new SearchHit(
                    "post",
                    "Post",
                    post.title(),
                    post.summary(),
                    post.aiSummary(),
                    "/post/" + post.slug(),
                    post.slug(),
                    post.publishedAt() != null ? OffsetDateTime.parse(post.publishedAt()) : null,
                    post.publishedAt() != null ? formatDate(OffsetDateTime.parse(post.publishedAt())) : "unknown",
                    "author: " + post.authorName() + " | views: " + post.viewCount(),
                        "[Read More]",
                        post.categoryName(),
                        post.categorySlug(),
                        post.viewCount(),
                        List.of() // ES might not return tags in this DTO yet
                );
                hits.add(hit);
            }

            hits.sort(hitComparator(sort));

            log.infof("Elasticsearch 搜索结果：%d 篇文章（共 %d 条）", hits.size(), searchResult.total());
            return new ElasticsearchResult(hits, searchResult.total(), true, false, true);

        } catch (ElasticsearchPostSearchService.ElasticsearchUnavailableException e) {
            log.warnf("Elasticsearch 服务不可用，回退到数据库搜索: %s", e.getMessage());
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        } catch (Exception e) {
            log.error("Elasticsearch 搜索失败，回退到数据库搜索", e);
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        }
    }

    private Map<Long, List<IndexController.TagItem>> loadTagsByPost(List<Post> posts, String lang) {
        Map<Long, List<IndexController.TagItem>> tagsByPost = new HashMap<>();
        List<Long> postIds = new ArrayList<>();
        for (Post post : posts) {
            if (post.id != null) {
                postIds.add(post.id);
                tagsByPost.put(post.id, new ArrayList<>());
            }
        }
        if (postIds.isEmpty()) {
            return tagsByPost;
        }
        List<PostTag> postTags = entityManager.createQuery(
                        "select postTag from PostTag postTag join fetch postTag.tag where postTag.post.id in :postIds",
                        PostTag.class)
                .setParameter("postIds", postIds)
                .getResultList();
        for (PostTag postTag : postTags) {
            List<IndexController.TagItem> tags = tagsByPost.get(postTag.post.id);
            if (tags != null) {
                tags.add(new IndexController.TagItem(
                        LanguageHelper.resolveLocalizedValue(postTag.tag.name, lang), postTag.tag.slug));
            }
        }
        return tagsByPost;
    }

    private SearchHit toPostHit(Post post, String lang,
                                Map<Long, List<IndexController.TagItem>> tagsByPost) {
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

        List<IndexController.TagItem> tags = tagsByPost.getOrDefault(post.id, List.of());

        OffsetDateTime displayDate = effectiveDate(post);
        long viewCount = 0;
        if (post.viewCount != null) {
            viewCount = post.viewCount;
        }
        return new SearchHit(
                "post",
                "Post",
                title,
                summary,
                LanguageHelper.resolveLocalizedValue(post.aiSummary, lang),
                "/post/" + post.slug,
                post.slug,
                displayDate,
                formatDate(displayDate),
                "slug: " + post.slug,
                "[Read More]",
                categoryName,
                categorySlug,
                viewCount,
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

        long postCount = PostTag.count("tag.id = ?1 and post.status = ?2 and post.deletedAt is null and post.visibility = 0 and post.publishedRevision is not null", tag.id, PostStatus.PUBLISHED);
        return new SearchHit(
                "tag",
                "Tag",
                name,
                description,
                null,
                "/tag/" + tag.slug,
                tag.slug,
                tag.createdAt,
                formatDate(tag.createdAt),
                "posts: " + postCount + " | slug: " + tag.slug,
                "[Browse Tag]",
                null,
                null,
                0,
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
                null,
                "/category/" + category.slug,
                category.slug,
                category.createdAt,
                formatDate(category.createdAt),
                "children: " + childCount + " | path: " + safe(category.path),
                "[Browse Category]",
                null,
                null,
                0,
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

        if ("hot".equals(sort)) {
            Comparator<SearchHit> byViewCount = Comparator
                    .comparingLong((SearchHit hit) -> hit.viewCount)
                    .reversed();
            return byViewCount.thenComparing(byDateDesc).thenComparing(byTitle);
        }
        if ("latest".equals(sort)) {
            return byDateDesc.thenComparing(byTypeWeight).thenComparing(byTitle);
        }
        return byTypeWeight.thenComparing(byDateDesc).thenComparing(byTitle);
    }

    private OffsetDateTime effectiveDate(Post post) {
        return post.publishedAt != null ? post.publishedAt : post.createdAt;
    }

    private OffsetDateTime dateThreshold(String date) {
        OffsetDateTime now = OffsetDateTime.now();
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

    private String buildDeepSearchUrl(String keyword, String type, String sort, String date) {
        StringBuilder url = new StringBuilder(buildSearchUrl(keyword, type, sort, date, 1));
        url.append("&deep=true");
        return url.toString();
    }

    private String buildDeepSearchQuery(String keyword, String type, String sort, String date, Integer page, String lang) {
        StringBuilder query = new StringBuilder();
        query.append("q=").append(urlEncode(keyword));
        query.append("&type=").append(urlEncode(type));
        query.append("&sort=").append(urlEncode(sort));
        query.append("&date=").append(urlEncode(date));
        query.append("&page=").append(page);
        query.append("&lang=").append(urlEncode(lang));
        return query.toString();
    }

    private boolean canUseDeepSearch(String searchType) {
        if ("all".equals(searchType)) {
            return true;
        }
        return "post".equals(searchType);
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

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return text;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    @TemplateData
    public record SearchHit(
            String type,
            String typeLabel,
            String title,
            String summary,
            String aiSummary,
            String url,
            String slug,
            OffsetDateTime date,
            String dateText,
            String meta,
            String actionLabel,
            String categoryName,
            String categorySlug,
            long viewCount,
            List<IndexController.TagItem> tags
    ) {
    }

    @TemplateData
    public record FilterLink(String label, String url, String cssClass) {
    }

    /**
     * Elasticsearch 搜索结果包装类
     */
    private record ElasticsearchResult(
            List<SearchHit> hits,
            long totalCount,
            boolean usedElasticsearch,
            boolean degraded,
            boolean alreadyPaged
    ) {
    }

    public record DeepSearchResponse(
            List<SearchHit> hits,
            long totalCount,
            boolean usedElasticsearch,
            boolean degraded
    ) {
    }
}
