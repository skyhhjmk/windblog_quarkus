package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.model.BlogRegion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Locale;

/**
 * Builds the article search request body. Pagination, sort, date range and the
 * parsed advanced filters are all part of the ES query, so the engine ranks
 * the whole result set before returning one page.
 */
final class PostSearchRequestBody {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> SEARCH_DOCUMENT_FIELDS = List.of(
            "title^5", "content", "summary", "translations^3", "seoTitle^2", "seoDescription",
            "aiSummary", "seoKeywords", "tags^2");

    private PostSearchRequestBody() {
    }

    static ObjectNode build(ElasticsearchPostSearchService.PostSearchRequest request,
                            String highlightPreTag, String highlightPostTag) {
        ObjectNode body = OBJECT_MAPPER.createObjectNode();
        body.put("from", request.from());
        body.put("size", request.size());
        body.put("track_total_hits", true);

        addSort(body.putArray("sort"), request.sort());

        ObjectNode bool = body.putObject("query").putObject("bool");
        ArrayNode filter = bool.putArray("filter");
        filter.addObject().putObject("term").put("status", "PUBLISHED");
        filter.addObject().putObject("term").put("visibility", "PUBLIC");
        filter.add(regionVisibility(request.region()));
        if (request.publishedAfter() != null) {
            filter.addObject().putObject("range").putObject("publishedAt")
                    .put("gte", request.publishedAfter().toString());
        }

        ArrayNode must = bool.putArray("must");
        if (request.title() != null) {
            must.addObject().putObject("match").putObject("title")
                    .put("query", request.title()).put("operator", "and");
        }
        if (request.content() != null) {
            addSearchDocumentClause(must, request.content());
        }
        if (request.author() != null) {
            addAuthorFilter(must, request.author());
        }
        if (request.user() != null) {
            addAuthorFilter(must, request.user());
        }
        String freeText = request.freeText() == null ? "" : request.freeText().trim();
        if (!freeText.isEmpty()) {
            addSearchDocumentClause(must, freeText);
        }
        if (must.isEmpty()) {
            must.addObject().putObject("match_all");
        }

        String highlightText = request.highlightText() == null ? "" : request.highlightText().trim();
        if (!highlightText.isEmpty()) {
            ObjectNode highlight = body.putObject("highlight");
            ObjectNode fields = highlight.putObject("fields");
            fields.putObject("title");
            fields.putObject("content").put("fragment_size", 150).put("number_of_fragments", 3);
            fields.putObject("summary");
            highlight.putArray("pre_tags").add(highlightPreTag);
            highlight.putArray("post_tags").add(highlightPostTag);
        }
        return body;
    }

    private static void addSearchDocumentClause(ArrayNode must, String value) {
        ObjectNode clause = must.addObject().putObject("bool");
        ArrayNode should = clause.putArray("should");
        ObjectNode multiMatch = should.addObject().putObject("multi_match");
        multiMatch.put("query", value);
        multiMatch.put("type", "best_fields");
        ArrayNode fields = multiMatch.putArray("fields");
        SEARCH_DOCUMENT_FIELDS.forEach(fields::add);
        ObjectNode slug = should.addObject().putObject("wildcard").putObject("slug");
        slug.put("value", "*" + value.toLowerCase(Locale.ROOT) + "*");
        slug.put("case_insensitive", true);
        clause.put("minimum_should_match", 1);
    }

    private static void addAuthorFilter(ArrayNode must, String value) {
        ObjectNode wildcard = must.addObject().putObject("wildcard").putObject("authorName");
        wildcard.put("value", "*" + value.toLowerCase(Locale.ROOT) + "*");
        wildcard.put("case_insensitive", true);
    }

    private static void addSort(ArrayNode sort, String sortMode) {
        if ("hot".equals(sortMode)) {
            addFieldSort(sort, "viewCount", true);
            addFieldSort(sort, "publishedAt", true);
        } else if ("latest".equals(sortMode)) {
            addFieldSort(sort, "publishedAt", true);
        } else {
            sort.addObject().putObject("_score").put("order", "desc");
            addFieldSort(sort, "publishedAt", true);
        }
        addFieldSort(sort, "id", false);
    }

    private static void addFieldSort(ArrayNode sort, String field, boolean descending) {
        ObjectNode options = sort.addObject().putObject(field);
        options.put("order", descending ? "desc" : "asc");
        if (descending) {
            options.put("missing", "_last");
        }
    }

    private static ObjectNode regionVisibility(String regionCode) {
        try {
            return (ObjectNode) OBJECT_MAPPER.readTree(
                    RegionVisibility.elasticsearchFilter(BlogRegion.fromCode(regionCode)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Region visibility filter is not valid JSON", exception);
        }
    }
}
