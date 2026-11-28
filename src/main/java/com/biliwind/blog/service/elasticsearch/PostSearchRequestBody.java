package com.biliwind.blog.service.elasticsearch;

import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.common.helper.SearchDictionaryCatalog;
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
            "aiSummary", "seoKeywords.text^2", "tags.text^3");

    private static final List<String> EXACT_DOCUMENT_FIELDS = List.of(
            "title.exact^5", "content.exact", "summary.exact", "translations.exact^3",
            "seoTitle.exact^2", "seoDescription.exact", "aiSummary.exact",
            "seoKeywords.exact^2", "tags.exact^3");

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
            addTitleFilter(must, request.title());
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

    private static void addTitleFilter(ArrayNode must, String value) {
        ObjectNode clause = must.addObject().putObject("bool");
        ArrayNode should = clause.putArray("should");

        ObjectNode exactKeyword = should.addObject().putObject("term").putObject("title.keyword");
        exactKeyword.put("value", value);
        exactKeyword.put("case_insensitive", true);
        exactKeyword.put("boost", 30.0);

        addPhraseBoost(should, "title.exact", value, 0, 12.0);

        ObjectNode tokenized = should.addObject().putObject("match").putObject("title");
        tokenized.put("query", value);
        tokenized.put("operator", "and");
        clause.put("minimum_should_match", 1);
    }

    private static void addSearchDocumentClause(ArrayNode must, String value) {
        ObjectNode clause = must.addObject().putObject("bool");
        ArrayNode should = clause.putArray("should");

        List<String> segmentedTerms = SearchDictionaryCatalog.segmentQuery(value);
        String tokenizedQuery = segmentedTerms.isEmpty() ? value : String.join(" ", segmentedTerms);

        // 1. 分词后结果：使用收紧后的 minimum_should_match，避免 3 词拆出高频虚词仅命中 2 词就召回
        ObjectNode tokenizedMatch = should.addObject().putObject("multi_match");
        tokenizedMatch.put("query", tokenizedQuery);
        tokenizedMatch.put("type", "best_fields");
        tokenizedMatch.put("minimum_should_match", "3<100% 6<75%");
        ArrayNode fields = tokenizedMatch.putArray("fields");
        SEARCH_DOCUMENT_FIELDS.forEach(fields::add);

        // 2. 仅关键词匹配（不经停用词过滤，保留完整原始输入语序）
        addPhraseBoost(should, "title.exact", value, 0, 25.0);
        addPhraseBoost(should, "title.exact", value, 1, 8.0);
        addPhraseBoost(should, "seoTitle.exact", value, 0, 12.0);
        addPhraseBoost(should, "summary.exact", value, 0, 10.0);
        addPhraseBoost(should, "translations.exact", value, 0, 10.0);
        addPhraseBoost(should, "content.exact", value, 0, 6.0);
        addPhraseBoost(should, "tags.exact", value, 0, 15.0);
        addPhraseBoost(should, "seoKeywords.exact", value, 0, 10.0);

        // 3. 完美匹配提权：如果输入的所有内容能完整/全词命中目标，大幅提高权重
        addExactTermBoost(should, "title.keyword", value, 60.0);
        addExactTermBoost(should, "tags", value, 35.0);
        addExactTermBoost(should, "seoKeywords", value, 25.0);
        addExactTermBoost(should, "slug", value.toLowerCase(Locale.ROOT), 30.0);

        ObjectNode allExactTerms = should.addObject().putObject("multi_match");
        allExactTerms.put("query", value);
        allExactTerms.put("type", "cross_fields");
        allExactTerms.put("operator", "and");
        allExactTerms.put("boost", 4.0);
        ArrayNode exactFields = allExactTerms.putArray("fields");
        EXACT_DOCUMENT_FIELDS.forEach(exactFields::add);

        ObjectNode slugWildcard = should.addObject().putObject("wildcard").putObject("slug");
        slugWildcard.put("value", "*" + value.toLowerCase(Locale.ROOT) + "*");
        slugWildcard.put("case_insensitive", true);
        slugWildcard.put("boost", 5.0);

        clause.put("minimum_should_match", 1);
    }

    private static void addExactTermBoost(ArrayNode should, String field, String value, double boost) {
        ObjectNode term = should.addObject().putObject("term").putObject(field);
        term.put("value", value);
        term.put("case_insensitive", true);
        term.put("boost", boost);
    }

    private static void addPhraseBoost(ArrayNode should, String field, String value, int slop, double boost) {
        ObjectNode phrase = should.addObject().putObject("match_phrase").putObject(field);
        phrase.put("query", value);
        phrase.put("slop", slop);
        phrase.put("boost", boost);
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
