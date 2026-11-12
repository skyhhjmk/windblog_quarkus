package com.biliwind.blog.controller;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.dto.SearchHighlightSegment;
import com.biliwind.blog.common.helper.SearchContentHelper;
import com.biliwind.blog.common.helper.SearchQueryParser;
import com.biliwind.blog.common.helper.SearchWeightHelper;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.helper.RegionVisibility;
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
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

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
    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @QueryParam("use-es")
    @DefaultValue("true")
    boolean useElasticsearch;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
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

        String cacheKey = null;
        if (!searchKeyword.isBlank() && cacheService != null) {
            String region = regionContext != null && regionContext.getCurrentRegion() != null
                    ? regionContext.getCurrentRegion().getCode()
                    : "global";
            String querySignature = String.join(":",
                    searchKeyword, searchType, searchSort, searchDate, lang, String.valueOf(currentPage),
                    String.valueOf(edgeNode), String.valueOf(deepSearchRequested), String.valueOf(useElasticsearch), region);
            cacheKey = CacheService.Keys.searchResults(Integer.toHexString(querySignature.hashCode()));
            java.util.Optional<SearchCacheResult> cachedResult = cacheService.get(cacheKey, SearchCacheResult.class);
            if (cachedResult.isPresent()) {
                SearchCacheResult cached = cachedResult.get();
                searchHits = cached.hits();
                totalCount = cached.totalCount();
                usedElasticsearch = cached.usedElasticsearch();
                esDegraded = cached.degraded();
                deepSearchFailed = cached.degraded();
                searchResultAlreadyPaged = cached.alreadyPaged();
                return renderSearchTemplate(
                        searchKeyword, searchType, searchSort, searchDate, lang, currentPage,
                        searchHits, totalCount, searchResultAlreadyPaged, usedElasticsearch,
                        esDegraded, edgeNode, deepSearchRequested, deepSearchFailed, httpHeaders);
            }
        }

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

        if (cacheKey != null && cacheService != null && !esDegraded) {
            cacheService.set(cacheKey, new SearchCacheResult(
                    searchHits, totalCount, usedElasticsearch, esDegraded, searchResultAlreadyPaged
            ), java.time.Duration.ofMinutes(3));
        }

        return renderSearchTemplate(
                searchKeyword, searchType, searchSort, searchDate, lang, currentPage,
                searchHits, totalCount, searchResultAlreadyPaged, usedElasticsearch,
                esDegraded, edgeNode, deepSearchRequested, deepSearchFailed, httpHeaders);
    }

    private TemplateInstance renderSearchTemplate(
            String searchKeyword,
            String searchType,
            String searchSort,
            String searchDate,
            String lang,
            int currentPage,
            List<SearchHit> searchHits,
            long totalCount,
            boolean searchResultAlreadyPaged,
            boolean usedElasticsearch,
            boolean esDegraded,
            boolean edgeNode,
            boolean deepSearchRequested,
            boolean deepSearchFailed,
            HttpHeaders httpHeaders) {
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
                .data("parsedQuery", SearchQueryParser.parse(searchKeyword))
                .data("hasAdvancedFilters", SearchQueryParser.parse(searchKeyword).hasFilters())
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
                .data("firstPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, 1))
                .data("lastPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, totalPages))
                .data("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, pageNumber)))
                .data("paginationN", "n".repeat(Math.min(totalPages, 7)))
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
    @Transactional
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
                                         @HeaderParam("X-WindBlog-Routed-From-Edge") String routedFromEdge,
                                         @Context io.vertx.ext.web.RoutingContext routingContext) {
        if (!nodeRoleService.isPrimaryNode()) {
            throw new NotFoundException();
        }
        // The marker header alone is client-controlled. The only legitimate
        // caller is PrimaryRoutedHttpExecutor replaying an edge request to the
        // primary's own loopback listener, so require that peer as well.
        if (!"true".equalsIgnoreCase(routedFromEdge) || !isLoopbackReplay(routingContext)) {
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

    static boolean isLoopbackReplay(io.vertx.ext.web.RoutingContext routingContext) {
        if (routingContext == null || routingContext.request() == null) {
            return false;
        }
        return isLoopbackAddress(routingContext.request().remoteAddress());
    }

    static boolean isLoopbackAddress(io.vertx.core.net.SocketAddress remoteAddress) {
        if (remoteAddress == null || !remoteAddress.isInetSocket()) {
            return false;
        }
        String host = remoteAddress.hostAddress() == null ? remoteAddress.host() : remoteAddress.hostAddress();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException exception) {
            return false;
        }
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

        String searchPattern = "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%";
        OffsetDateTime threshold = dateThreshold(date);

        if (sort.isBlank()) {
            int safePage = Math.min(MAX_FALLBACK_PAGE, Math.max(1, page));
            int candidateLimit = Math.min(1000, Math.max(100, safePage * PAGE_SIZE));
            PostFallbackPage fallbackPosts = findPostFallbackCandidates(keyword, searchPattern, threshold, candidateLimit);
            List<SearchHit> candidates = new ArrayList<>();
            Map<Long, List<IndexController.TagItem>> tagsByPost = loadTagsByPost(fallbackPosts.items(), lang);
            for (Post post : fallbackPosts.items()) {
                candidates.add(toPostHit(post, lang, tagsByPost, keyword));
            }
            candidates.sort(hitComparator(sort));
            int fromIndex = Math.min(candidates.size(), (safePage - 1) * PAGE_SIZE);
            int toIndex = Math.min(candidates.size(), fromIndex + PAGE_SIZE);
            List<SearchHit> hits = new ArrayList<>(candidates.subList(fromIndex, toIndex));
            return new ElasticsearchResult(hits, fallbackPosts.total(), false, true, true);
        }

        String orderBy = "latest".equals(sort)
                ? "p.published_at DESC NULLS LAST, p.id DESC"
                : "p.view_count DESC NULLS LAST, p.published_at DESC NULLS LAST, p.id DESC";
        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.nativeSqlFilter("p.visibility_regions", regionContext.getCurrentRegion());

        SearchQueryParser parsed = SearchQueryParser.parse(keyword);
        Map<String, Object> params = new HashMap<>();
        String where = buildPostFilterWhere(parsed, keyword, searchPattern, regionFilter, threshold, params);

        String selectSql = "select p.* from posts p where " + where + " order by " + orderBy;
        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(selectSql, Post.class);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            selectQuery.setParameter(entry.getKey(), entry.getValue());
        }
        int safePage = Math.max(1, page);
        selectQuery.setFirstResult((safePage - 1) * PAGE_SIZE);
        selectQuery.setMaxResults(PAGE_SIZE);

        @SuppressWarnings("unchecked")
        List<Post> posts = selectQuery.getResultList();
        List<SearchHit> hits = new ArrayList<>();
        Map<Long, List<IndexController.TagItem>> tagsByPost = loadTagsByPost(posts, lang);
        for (Post post : posts) {
            hits.add(toPostHit(post, lang, tagsByPost, keyword));
        }

        String countSql = "select count(*) from posts p where " + where;
        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(countSql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            countQuery.setParameter(entry.getKey(), entry.getValue());
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
        int candidateLimit = Math.min(1000, Math.max(100, safePage * PAGE_SIZE));

        PostFallbackPage posts = findPostFallbackCandidates(keyword, searchPattern, threshold, candidateLimit);
        TaxonomyFallbackPage tags = findTaxonomyFallbackCandidates("tags", searchPattern, threshold, candidateLimit);
        TaxonomyFallbackPage categories = findTaxonomyFallbackCandidates("categories", searchPattern, threshold, candidateLimit);

        List<SearchHit> candidates = new ArrayList<>();
        Map<Long, List<IndexController.TagItem>> tagsByPost = loadTagsByPost(posts.items(), lang);
        for (Post post : posts.items()) {
            candidates.add(toPostHit(post, lang, tagsByPost, keyword));
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

    private String buildPostFilterWhere(SearchQueryParser parsed, String keyword, String searchPattern,
                                        RegionVisibility.NamedFilter regionFilter, OffsetDateTime threshold,
                                        Map<String, Object> params) {
        if (parsed.hasFilters()) {
            StringBuilder wb = new StringBuilder("p.status = 1 and p.deleted_at is null and p.visibility = 0 and p.published_revision_id is not null and " + regionFilter.fragment() + " ");
            params.putAll(regionFilter.parameters());
            if (threshold != null) {
                wb.append("and p.published_at >= :threshold ");
                params.put("threshold", threshold);
            }
            if (parsed.title() != null) {
                wb.append("and lower(p.title::text) like :titlePattern escape '\\' ");
                params.put("titlePattern", "%" + escapeLike(parsed.title().toLowerCase(Locale.ROOT)) + "%");
            }
            if (parsed.content() != null) {
                wb.append("and (p.search_document @@ plainto_tsquery('simple', :rawContent) or lower(coalesce(p.summary::text, '')) like :contentPattern escape '\\') ");
                params.put("rawContent", parsed.content());
                params.put("contentPattern", "%" + escapeLike(parsed.content().toLowerCase(Locale.ROOT)) + "%");
            }
            if (parsed.author() != null) {
                wb.append("and lower(coalesce(p.author_name, '')) like :authorPattern escape '\\' ");
                params.put("authorPattern", "%" + escapeLike(parsed.author().toLowerCase(Locale.ROOT)) + "%");
            }
            if (parsed.user() != null) {
                wb.append("and lower(coalesce(p.author_name, '')) like :userPattern escape '\\' ");
                params.put("userPattern", "%" + escapeLike(parsed.user().toLowerCase(Locale.ROOT)) + "%");
            }
            if (!parsed.freeText().isEmpty()) {
                wb.append("and (p.search_document @@ plainto_tsquery('simple', :freeText) or lower(p.slug) like :freePattern escape '\\' or lower(p.title::text) like :freePattern escape '\\' or lower(coalesce(p.summary::text, '')) like :freePattern escape '\\') ");
                params.put("freeText", parsed.freeText());
                params.put("freePattern", "%" + escapeLike(parsed.freeText().toLowerCase(Locale.ROOT)) + "%");
            }
            return wb.toString();
        }

        StringBuilder wb = new StringBuilder("p.status = 1 and p.deleted_at is null and p.visibility = 0 and p.published_revision_id is not null and " + regionFilter.fragment() + " ");
        params.putAll(regionFilter.parameters());
        params.put("keyword", keyword);
        params.put("searchPattern", searchPattern);
        wb.append("and (p.search_document @@ plainto_tsquery('simple', :keyword) or lower(p.slug) like :searchPattern escape '\\' or lower(p.title::text) like :searchPattern escape '\\' or lower(coalesce(p.summary::text, '')) like :searchPattern escape '\\') ");
        if (threshold != null) {
            wb.append("and p.published_at >= :threshold ");
            params.put("threshold", threshold);
        }
        return wb.toString();
    }

    private PostFallbackPage findPostFallbackCandidates(
            String keyword, String searchPattern, OffsetDateTime threshold, int limit) {
        String orderBy = "p.published_at DESC NULLS LAST, p.id DESC";
        RegionVisibility.NamedFilter regionFilter =
                RegionVisibility.nativeSqlFilter("p.visibility_regions", regionContext.getCurrentRegion());

        SearchQueryParser parsed = SearchQueryParser.parse(keyword);
        Map<String, Object> params = new HashMap<>();
        String where = buildPostFilterWhere(parsed, keyword, searchPattern, regionFilter, threshold, params);

        jakarta.persistence.Query countQuery = entityManager.createNativeQuery("select count(*) from posts p where " + where);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            countQuery.setParameter(entry.getKey(), entry.getValue());
        }
        long total = ((Number) countQuery.getSingleResult()).longValue();

        jakarta.persistence.Query selectQuery = entityManager.createNativeQuery(
                "select p.* from posts p where " + where + " order by " + orderBy, Post.class);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            selectQuery.setParameter(entry.getKey(), entry.getValue());
        }
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
        List<IndexController.CategoryItem> categories = new ArrayList<>();
        JsonNode categoryNodes = hitNode.path("categories");
        if (categoryNodes.isArray()) {
            for (JsonNode categoryNode : categoryNodes) {
                categories.add(new IndexController.CategoryItem(
                        categoryNode.path("name").asText(""), categoryNode.path("slug").asText("")));
            }
        }
        if (categories.isEmpty() && hitNode.hasNonNull("categorySlug")) {
            categories.add(new IndexController.CategoryItem(
                    hitNode.path("categoryName").asText(""), hitNode.path("categorySlug").asText("")));
        }

        String title = hitNode.path("title").asText("");
        String summary = hitNode.path("summary").asText("");
        String meta = hitNode.path("meta").asText("");
        String authorName = hitNode.path("authorName").asText("");
        if (authorName.isBlank()) {
            authorName = authorNameFromMeta(meta);
        }
        int weight = hitNode.path("weight").asInt(0);
        return new SearchHit(
                hitNode.path("type").asText("post"),
                hitNode.path("typeLabel").asText("Post"),
                title,
                summary,
                hitNode.path("aiSummary").asText(""),
                hitNode.path("url").asText(""),
                hitNode.path("slug").asText(""),
                date,
                dateText,
                meta,
                hitNode.path("actionLabel").asText("[Read More]"),
                textOrNull(hitNode.path("categoryName")),
                textOrNull(hitNode.path("categorySlug")),
                hitNode.path("viewCount").asLong(0),
                tags,
                categories,
                parseHighlightSegments(hitNode.path("titleSegments"), title),
                parseHighlightSegments(hitNode.path("summarySegments"), summary),
                parseContentSnippets(hitNode.path("contentSnippets")),
                authorName,
                weight
        );
    }

    private String authorNameFromMeta(String meta) {
        if (meta == null) {
            return "";
        }
        int authorStart = meta.indexOf("author:");
        if (authorStart < 0) {
            return "";
        }
        int nameStart = authorStart + "author:".length();
        int nameEnd = meta.indexOf('|', nameStart);
        String authorName = meta.substring(nameStart, nameEnd < 0 ? meta.length() : nameEnd).trim();
        return authorName.equalsIgnoreCase("unknown") ? "" : authorName;
    }

    private List<SearchHighlightSegment> parseHighlightSegments(JsonNode segmentNodes, String fallback) {
        List<SearchHighlightSegment> segments = new ArrayList<>();
        if (segmentNodes.isArray()) {
            for (JsonNode segmentNode : segmentNodes) {
                String text = segmentNode.path("text").asText("");
                if (!text.isEmpty()) {
                    segments.add(new SearchHighlightSegment(text, segmentNode.path("highlighted").asBoolean(false)));
                }
            }
        }
        if (segments.isEmpty() && fallback != null && !fallback.isEmpty()) {
            segments.add(new SearchHighlightSegment(fallback, false));
        }
        return segments;
    }

    private List<List<SearchHighlightSegment>> parseContentSnippets(JsonNode snippetNodes) {
        List<List<SearchHighlightSegment>> snippets = new ArrayList<>();
        if (!snippetNodes.isArray()) {
            return snippets;
        }
        for (JsonNode snippetNode : snippetNodes) {
            List<SearchHighlightSegment> segments = parseHighlightSegments(snippetNode, null);
            if (!segments.isEmpty()) {
                snippets.add(segments);
            }
        }
        return snippets;
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

            List<Long> postIds = searchResult.posts().stream()
                    .map(ElasticsearchPostSearchService.SearchedPost::id)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();

            Map<Long, Post> postMap = postIds.isEmpty() ? Map.of() :
                    entityManager.createQuery(
                            "select p from Post p left join fetch p.publishedRevision left join fetch p.category where p.id in :postIds",
                            Post.class)
                            .setParameter("postIds", postIds)
                            .getResultList()
                            .stream()
                            .collect(java.util.stream.Collectors.toMap(p -> p.id, p -> p, (a, b) -> a));

            for (ElasticsearchPostSearchService.SearchedPost post : searchResult.posts()) {
                OffsetDateTime publishedAt = resolveSearchDate(post);
                if (threshold != null && publishedAt != null && publishedAt.isBefore(threshold)) {
                    continue;
                }

                Post dbPost = post.id() != null ? postMap.get(post.id()) : null;

                Map<String, String> titles = (dbPost != null && dbPost.title != null && !dbPost.title.isEmpty())
                        ? dbPost.title
                        : post.titles();

                Map<String, String> summaries = (dbPost != null && dbPost.summary != null && !dbPost.summary.isEmpty())
                        ? dbPost.summary
                        : post.summaries();

                Map<String, String> aiSummaries = (dbPost != null && dbPost.aiSummary != null && !dbPost.aiSummary.isEmpty())
                        ? dbPost.aiSummary
                        : post.aiSummaries();

                Map<String, String> contents = post.contents();
                if (dbPost != null && dbPost.publishedRevision != null && dbPost.publishedRevision.contentMarkdown != null) {
                    Map<String, String> dbContents = new java.util.HashMap<>();
                    for (Map.Entry<String, String> entry : dbPost.publishedRevision.contentMarkdown.entrySet()) {
                        if (entry.getValue() != null && !entry.getValue().isBlank()) {
                            dbContents.put(entry.getKey(), SearchContentHelper.toSearchableText(entry.getValue(), dbPost.renderType));
                        }
                    }
                    if (!dbContents.isEmpty()) {
                        contents = dbContents;
                    }
                }

                String displayTitle = LanguageHelper.resolveLocalizedValue(titles, lang);
                if (displayTitle == null || displayTitle.isBlank()) {
                    displayTitle = post.title(lang);
                }
                if (displayTitle == null || displayTitle.isBlank()) {
                    displayTitle = post.slug();
                }

                String displaySummary = LanguageHelper.resolveLocalizedValue(summaries, lang);
                if (displaySummary == null || displaySummary.isBlank()) {
                    displaySummary = post.summary(lang);
                }
                if (displaySummary == null || displaySummary.isBlank()) {
                    displaySummary = "No summary available.";
                }

                String displayAiSummary = LanguageHelper.resolveLocalizedValue(aiSummaries, lang);
                if (displayAiSummary == null || displayAiSummary.isBlank()) {
                    displayAiSummary = post.aiSummary(lang);
                }

                String displayCategoryName = (dbPost != null && dbPost.category != null && dbPost.category.name != null)
                        ? LanguageHelper.resolveLocalizedValue(dbPost.category.name, lang)
                        : post.categoryName();

                List<SearchHighlightSegment> titleSegments;
                if (displayTitle.equals(post.title()) && post.titleSegments() != null && !post.titleSegments().isEmpty()) {
                    titleSegments = post.titleSegments();
                } else {
                    titleSegments = fallbackHighlight(displayTitle, keyword);
                }

                List<SearchHighlightSegment> summarySegments;
                if (displaySummary.equals(post.summary()) && post.summarySegments() != null && !post.summarySegments().isEmpty()) {
                    summarySegments = post.summarySegments();
                } else {
                    summarySegments = fallbackHighlight(displaySummary, keyword);
                }

                int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contents, keyword, lang);

                SearchHit hit = new SearchHit(
                    "post",
                    "Post",
                    displayTitle,
                    displaySummary,
                    displayAiSummary,
                    languageContext.localize("/post/" + post.slug()),
                    post.slug(),
                    publishedAt,
                    publishedAt != null ? formatDate(publishedAt) : "unknown",
                    "author: " + post.authorName() + " | views: " + post.viewCount(),
                        "[Read More]",
                        displayCategoryName,
                        post.categorySlug(),
                        post.viewCount(),
                        List.of(),
                        post.categories().stream().map(category -> new IndexController.CategoryItem(
                                category.name(), category.slug())).toList(),
                        titleSegments,
                        summarySegments,
                        post.contentSnippets(),
                        resolveSearchAuthorName(post.authorId(), post.authorName()),
                        weight
                );
                hits.add(hit);
            }

            hits.sort(hitComparator(sort));

            log.infof("Elasticsearch 搜索结果：%d 篇文章（共 %d 条）", hits.size(), searchResult.total());
            return new ElasticsearchResult(hits, searchResult.total(), true, false, true);

        } catch (ElasticsearchPostSearchService.ElasticsearchUnavailableException e) {
            log.warnf("Elasticsearch 服务不可用，回退到数据库搜索: %s",
                    SensitiveMessageSanitizer.sanitize(e.getMessage()));
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        } catch (Exception e) {
            log.error("Elasticsearch 搜索失败，回退到数据库搜索", e);
            return searchWithDatabaseFallback(keyword, type, sort, date, lang, page);
        }
    }

    private OffsetDateTime resolveSearchDate(ElasticsearchPostSearchService.SearchedPost post) {
        OffsetDateTime date = parseSearchPublishedAt(post.publishedAt());
        if (date == null) {
            date = parseSearchPublishedAt(post.createdAt());
        }
        if (date == null && post.id() != null) {
            Post databasePost = entityManager.find(Post.class, post.id());
            if (databasePost != null) {
                date = effectiveDate(databasePost);
            }
        }
        return date;
    }

    private OffsetDateTime parseSearchPublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeException exception) {
            try {
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeException ignored) {
                try {
                    return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC);
                } catch (DateTimeException invalidDate) {
                    log.debugf("无法解析 Elasticsearch 文章发布时间，保留搜索结果: %s", value);
                    return null;
                }
            }
        }
    }

    private String resolveSearchAuthorName(Long authorId, String indexedAuthorName) {
        if (indexedAuthorName != null && !indexedAuthorName.isBlank()
                && !"unknown".equalsIgnoreCase(indexedAuthorName.trim())) {
            return indexedAuthorName.trim();
        }
        if (authorId != null && authorId > 0) {
            User user = entityManager.find(User.class, authorId);
            if (user != null && user.username != null && !user.username.isBlank()) {
                return user.username.trim();
            }
        }
        return "Unknown";
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
                                Map<Long, List<IndexController.TagItem>> tagsByPost,
                                String keyword) {
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
        List<IndexController.CategoryItem> categories = post.categories.stream()
                .filter(category -> category != null && category.enabled)
                .map(category -> new IndexController.CategoryItem(
                        LanguageHelper.resolveLocalizedValue(category.name, lang), category.slug))
                .toList();
        if (categories.isEmpty() && post.category != null && post.category.enabled) {
            categories = List.of(new IndexController.CategoryItem(categoryName, categorySlug));
        }

        List<IndexController.TagItem> tags = tagsByPost.getOrDefault(post.id, List.of());

        OffsetDateTime displayDate = effectiveDate(post);
        long viewCount = 0;
        if (post.viewCount != null) {
            viewCount = post.viewCount;
        }
        String content = post.publishedRevision == null || post.publishedRevision.contentMarkdown == null
                ? ""
                : LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
        String searchableText = SearchContentHelper.toSearchableText(content, post.renderType);
        List<List<SearchHighlightSegment>> contentSnippets = fallbackContentSnippets(
                searchableText, keyword);

        Map<String, String> titles = post.title != null ? post.title : Map.of();
        Map<String, String> contentsMap = new java.util.HashMap<>();
        if (post.publishedRevision != null && post.publishedRevision.contentMarkdown != null) {
            for (Map.Entry<String, String> entry : post.publishedRevision.contentMarkdown.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    contentsMap.put(entry.getKey(), SearchContentHelper.toSearchableText(entry.getValue(), post.renderType));
                }
            }
        }
        int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contentsMap, keyword, lang);
        return new SearchHit(
                "post",
                "Post",
                title,
                summary,
                LanguageHelper.resolveLocalizedValue(post.aiSummary, lang),
                languageContext.localize("/post/" + post.slug),
                post.slug,
                displayDate,
                formatDate(displayDate),
                "slug: " + post.slug,
                "[Read More]",
                categoryName,
                categorySlug,
                viewCount,
                tags,
                categories,
                fallbackHighlight(title, keyword),
                fallbackHighlight(summary, keyword),
                contentSnippets,
                PostAuthorHelper.displayName(post),
                weight
        );
    }

    private List<SearchHighlightSegment> fallbackHighlight(String value, String keyword) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> terms = highlightTerms(keyword);
        if (terms.isEmpty()) {
            return List.of(new SearchHighlightSegment(value, false));
        }

        List<SearchHighlightSegment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int matchStart = -1;
            String matchedTerm = null;
            for (String term : terms) {
                int candidate = value.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT), cursor);
                if (candidate >= 0 && (matchStart < 0 || candidate < matchStart
                        || (candidate == matchStart && term.length() > matchedTerm.length()))) {
                    matchStart = candidate;
                    matchedTerm = term;
                }
            }
            if (matchStart < 0) {
                addPlainSegment(segments, value.substring(cursor));
                break;
            }
            addPlainSegment(segments, value.substring(cursor, matchStart));
            segments.add(new SearchHighlightSegment(value.substring(matchStart, matchStart + matchedTerm.length()), true));
            cursor = matchStart + matchedTerm.length();
        }
        return segments.isEmpty() ? List.of(new SearchHighlightSegment(value, false)) : segments;
    }

    private List<List<SearchHighlightSegment>> fallbackContentSnippets(String content, String keyword) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> terms = highlightTerms(keyword);
        if (terms.isEmpty()) {
            return List.of();
        }
        int matchStart = -1;
        String matchedTerm = null;
        String foldedContent = content.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            int candidate = foldedContent.indexOf(term.toLowerCase(Locale.ROOT));
            if (candidate >= 0 && (matchStart < 0 || candidate < matchStart
                    || (candidate == matchStart && term.length() > matchedTerm.length()))) {
                matchStart = candidate;
                matchedTerm = term;
            }
        }
        if (matchStart < 0) {
            return List.of();
        }

        int start = Math.max(0, matchStart - 90);
        int end = Math.min(content.length(), matchStart + matchedTerm.length() + 110);
        String excerpt = content.substring(start, end);
        List<SearchHighlightSegment> segments = new ArrayList<>();
        if (start > 0) {
            segments.add(new SearchHighlightSegment("…", false));
        }
        segments.addAll(fallbackHighlight(excerpt, keyword));
        if (end < content.length()) {
            segments.add(new SearchHighlightSegment("…", false));
        }
        return List.of(segments);
    }

    private List<String> highlightTerms(String keyword) {
        return SearchWeightHelper.extractTerms(keyword);
    }

    private void addPlainSegment(List<SearchHighlightSegment> segments, String text) {
        if (!text.isEmpty()) {
            segments.add(new SearchHighlightSegment(text, false));
        }
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
                List.of(),
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
                List.of(),
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

        Comparator<SearchHit> byWeightDesc = Comparator
                .comparingInt((SearchHit hit) -> hit.weight)
                .reversed();

        if ("hot".equals(sort)) {
            Comparator<SearchHit> byViewCount = Comparator
                    .comparingLong((SearchHit hit) -> hit.viewCount)
                    .reversed();
            return byViewCount.thenComparing(byWeightDesc).thenComparing(byDateDesc).thenComparing(byTitle);
        }
        if ("latest".equals(sort)) {
            return byDateDesc.thenComparing(byWeightDesc).thenComparing(byTypeWeight).thenComparing(byTitle);
        }
        return byWeightDesc.thenComparing(byTypeWeight).thenComparing(byDateDesc).thenComparing(byTitle);
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
    @RegisterForReflection
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
            List<IndexController.TagItem> tags,
            List<IndexController.CategoryItem> categories,
            List<SearchHighlightSegment> titleSegments,
            List<SearchHighlightSegment> summarySegments,
            List<List<SearchHighlightSegment>> contentSnippets,
            String authorName,
            int weight
    ) {
        public SearchHit(String type, String typeLabel, String title, String summary, String aiSummary,
                         String url, String slug, OffsetDateTime date, String dateText, String meta,
                         String actionLabel, String categoryName, String categorySlug, long viewCount,
                         List<IndexController.TagItem> tags, List<IndexController.CategoryItem> categories) {
            this(type, typeLabel, title, summary, aiSummary, url, slug, date, dateText, meta, actionLabel,
                    categoryName, categorySlug, viewCount, tags, categories,
                    title == null || title.isEmpty() ? List.of() : List.of(new SearchHighlightSegment(title, false)),
                    summary == null || summary.isEmpty() ? List.of() : List.of(new SearchHighlightSegment(summary, false)),
                    List.of(), "", 0);
        }

        public SearchHit(String type, String typeLabel, String title, String summary, String aiSummary,
                         String url, String slug, OffsetDateTime date, String dateText, String meta,
                         String actionLabel, String categoryName, String categorySlug, long viewCount,
                         List<IndexController.TagItem> tags, List<IndexController.CategoryItem> categories,
                         List<SearchHighlightSegment> titleSegments,
                         List<SearchHighlightSegment> summarySegments,
                         List<List<SearchHighlightSegment>> contentSnippets,
                         String authorName) {
            this(type, typeLabel, title, summary, aiSummary, url, slug, date, dateText, meta, actionLabel,
                    categoryName, categorySlug, viewCount, tags, categories,
                    titleSegments, summarySegments, contentSnippets, authorName, 0);
        }
    }

    @TemplateData
    public record FilterLink(String label, String url, String cssClass) {
    }

    @RegisterForReflection
    public record SearchCacheResult(
            List<SearchHit> hits,
            long totalCount,
            boolean usedElasticsearch,
            boolean degraded,
            boolean alreadyPaged
    ) {
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
