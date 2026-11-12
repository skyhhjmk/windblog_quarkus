package com.biliwind.blog.service.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ES query must carry the whole search intent. In particular pagination
 * and sort are part of the request body, so the engine orders the complete
 * match set before returning a page instead of the caller re-sorting ten rows.
 */
class PostSearchRequestBodyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ElasticsearchPostSearchService.PostSearchRequest request(String sort,
                                                                     OffsetDateTime publishedAfter,
                                                                     String freeText,
                                                                     String title,
                                                                     String content,
                                                                     String author,
                                                                     int from) {
        return new ElasticsearchPostSearchService.PostSearchRequest(
                freeText, title, content, author, null, sort, publishedAfter, "cn",
                from, 10, freeText);
    }

    @Test
    void paginationAndLatestSortArePushedIntoTheQuery() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("latest", null, "quarkus", null, null, null, 20), "<em>", "</em>").toString());

        assertEquals(20, body.path("from").asInt());
        assertEquals(10, body.path("size").asInt());
        assertTrue(body.path("track_total_hits").asBoolean());

        JsonNode sort = body.path("sort");
        assertEquals("publishedAt", sort.get(0).fieldNames().next());
        assertEquals("desc", sort.get(0).path("publishedAt").path("order").asText());
        assertEquals("_last", sort.get(0).path("publishedAt").path("missing").asText());
        assertEquals("id", sort.get(1).fieldNames().next());
    }

    @Test
    void hotSortRanksByViewCountNotOnlyByThePage() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("hot", null, "quarkus", null, null, null, 0), "<em>", "</em>").toString());

        JsonNode sort = body.path("sort");
        assertEquals("viewCount", sort.get(0).fieldNames().next());
        assertEquals("desc", sort.get(0).path("viewCount").path("order").asText());
        assertEquals("publishedAt", sort.get(1).fieldNames().next());
    }

    @Test
    void defaultSortPushesRelevanceOrder() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("", null, "quarkus", null, null, null, 0), "<em>", "</em>").toString());

        assertEquals("_score", body.path("sort").get(0).fieldNames().next());
    }

    @Test
    void dateFilterIsARangeFilterOnPublishedAt() throws Exception {
        OffsetDateTime threshold = OffsetDateTime.parse("2026-04-01T00:00:00Z");
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("", threshold, "quarkus", null, null, null, 0), "<em>", "</em>").toString());

        JsonNode filters = body.path("query").path("bool").path("filter");
        JsonNode range = null;
        for (JsonNode filter : filters) {
            if (filter.has("range")) {
                range = filter.path("range").path("publishedAt");
            }
        }
        assertEquals(threshold, OffsetDateTime.parse(range.path("gte").asText()));
    }

    @Test
    void advancedFiltersAndFreeTextAllNarrowTheSameQuery() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("", null, "hello world", "Quarkus", "reactive", "sky", 0), "<em>", "</em>").toString());

        String must = body.path("query").path("bool").path("must").toString();
        assertTrue(must.contains("\"title\""), must);
        assertTrue(must.contains("reactive"), must);
        assertTrue(must.contains("authorName"), must);
        assertTrue(must.contains("hello world"), must);

        String filter = body.path("query").path("bool").path("filter").toString();
        assertTrue(filter.contains("\"status\":\"PUBLISHED\""), filter);
        assertTrue(filter.contains("\"visibility\":\"PUBLIC\""), filter);
        assertTrue(filter.contains("visibilityRegions"), filter);
    }

    @Test
    void emptyKeywordFallsBackToMatchAllWithNoHighlight() throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(PostSearchRequestBody.build(
                request("", null, "", null, null, null, 0), "<em>", "</em>").toString());

        String must = body.path("query").path("bool").path("must").toString();
        assertTrue(must.contains("match_all"), must);
        assertFalse(body.has("highlight"));
    }
}
