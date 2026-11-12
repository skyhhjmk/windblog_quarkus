package com.biliwind.blog.controller;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.common.dto.PaginationPage;
import com.biliwind.blog.common.dto.SearchHighlightSegment;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.common.helper.SearchQueryParser;
import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.edge.EdgePersistentChannelClient;
import com.biliwind.blog.service.edge.NodeRoleService;
import com.biliwind.blog.service.edge.RoutedHttpExchange;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import com.biliwind.blog.service.search.PostSearchQuery;
import com.biliwind.blog.service.search.PostSearchResult;
import com.biliwind.blog.service.search.PostSearchService;
import com.biliwind.blog.service.search.SearchHit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.TemplateData;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Path("/search")
public class SearchController {

    private static final Logger log = Logger.getLogger(SearchController.class);
    private static final int PAGE_SIZE = 10;
    private static final int MAX_FALLBACK_PAGE = 1000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    PageRenderer pageRenderer;

    @Inject
    LanguageContext languageContext;

    @Inject
    PostSearchService searchService;

    @Inject
    ElasticsearchPostSearchService elasticsearchPostSearchService;

    @Inject
    NodeRoleService nodeRoleService;
    @Inject
    EdgePersistentChannelClient edgePersistentChannelClient;

    @Inject
    RegionContext regionContext;
    @Inject
    CacheService cacheService;

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
        String lang = languageContext.getLang();
        PostSearchQuery query = PostSearchQuery.of(
                keyword, type, sort, date, lang, regionContext.getCurrentRegion(),
                currentPage, PAGE_SIZE, useElasticsearch);

        String searchKeyword = query.rawKeyword();
        String searchType = query.type();
        String searchSort = query.sort();
        String searchDate = query.date();

        List<SearchHit> searchHits;
        long totalCount;
        boolean searchResultAlreadyPaged = true;
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

        PostSearchResult result;
        if (deepSearchRequested && !searchKeyword.isBlank() && canUseDeepSearch(searchType)) {
            result = searchPrimaryWithElasticsearch(query);
            deepSearchFailed = result.degraded();
        } else {
            result = searchService.search(query);
        }
        searchHits = result.hits();
        totalCount = result.totalCount();
        usedElasticsearch = result.usedElasticsearch();
        esDegraded = result.degraded();
        searchResultAlreadyPaged = result.alreadyPaged();

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

        return pageRenderer.render(PageView.SEARCH, httpHeaders, new PageRenderModel()
                .put("language", lang)
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(httpHeaders))
                .put("pageTitle", buildPageTitle(searchKeyword))
                .put("searchKeyword", searchKeyword)
                .put("parsedQuery", SearchQueryParser.parse(searchKeyword))
                .put("hasAdvancedFilters", SearchQueryParser.parse(searchKeyword).hasFilters())
                .put("searchType", searchType)
                .put("searchSort", searchSort)
                .put("searchDate", searchDate)
                .put("hasKeyword", !searchKeyword.isBlank())
                .put("totalCount", totalCount)
                .put("currentPage", currentPage)
                .put("totalPages", totalPages)
                .put("hasPrevPage", currentPage > 1)
                .put("hasNextPage", currentPage < totalPages)
                .put("prevPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, Math.max(1, currentPage - 1)))
                .put("nextPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, Math.min(totalPages, currentPage + 1)))
                .put("firstPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, 1))
                .put("lastPageUrl", buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, totalPages))
                .put("paginationPages", buildPaginationPages(currentPage, totalPages,
                        pageNumber -> buildSearchUrl(searchKeyword, searchType, searchSort, searchDate, pageNumber)))
                .put("paginationN", "n".repeat(Math.min(totalPages, 7)))
                .put("typeLinks", buildTypeLinks(searchKeyword, searchType, searchSort, searchDate))
                .put("sortLinks", buildSortLinks(searchKeyword, searchType, searchSort, searchDate))
                .put("dateLinks", buildDateLinks(searchKeyword, searchType, searchSort, searchDate))
                .put("hits", pageItems)
                .put("usedElasticsearch", usedElasticsearch)
                .put("esDegraded", esDegraded)
                .put("edgeNode", edgeNode)
                .put("deepSearchRequested", deepSearchRequested)
                .put("deepSearchFailed", deepSearchFailed)
                .put("deepSearchUrl", buildDeepSearchUrl(searchKeyword, searchType, searchSort, searchDate)));
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
            return elasticsearchPostSearchService.suggestPosts(keyword);
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
        PostSearchResult result = searchService.search(PostSearchQuery.of(
                keyword, type, sort, date, lang, regionContext.getCurrentRegion(),
                currentPage, PAGE_SIZE, true));
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

    private PostSearchResult searchPrimaryWithElasticsearch(PostSearchQuery query) {
        try {
            String searchQuery = buildDeepSearchQuery(
                    query.rawKeyword(), query.type(), query.sort(), query.date(), query.page(), query.lang());
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", MediaType.APPLICATION_JSON);

            RoutedHttpExchange.Request request = new RoutedHttpExchange.Request(
                    "GET",
                    "/search/internal/deep",
                    searchQuery,
                    headers,
                    new byte[0]
            );

            RoutedHttpExchange.Response response = edgePersistentChannelClient.forwardWriteRequest(request);
            if (response.status() != 200) {
                log.warnf("深度搜索回源失败，状态码: %d", response.status());
                return searchService.search(query);
            }
            return parseDeepSearchResponse(response.body(), query);
        } catch (Exception exception) {
            log.error("深度搜索回源失败，使用边缘本地数据库搜索", exception);
            return searchService.search(query);
        }
    }

    private PostSearchResult parseDeepSearchResponse(byte[] responseBody, PostSearchQuery query) throws Exception {
        if (responseBody == null || responseBody.length == 0) {
            return searchService.search(query);
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
        return new PostSearchResult(hits, totalCount, usedElasticsearch, degraded, true);
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
        PostSearchResult fallback = searchService.search(PostSearchQuery.of(
                searchKeyword, "all", "", "", lang, regionContext.getCurrentRegion(), 1, PAGE_SIZE, false));
        List<String> suggestions = new ArrayList<>();
        for (SearchHit hit : fallback.hits()) {
            if (suggestions.size() >= 10) {
                break;
            }
            if (hit.title() == null || hit.title().isBlank()) {
                continue;
            }
            if (suggestions.contains(hit.title())) {
                continue;
            }
            suggestions.add(hit.title());
        }
        return suggestions;
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
        return "all".equals(searchType) || "post".equals(searchType);
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

    public record DeepSearchResponse(
            List<SearchHit> hits,
            long totalCount,
            boolean usedElasticsearch,
            boolean degraded
    ) {
    }
}
