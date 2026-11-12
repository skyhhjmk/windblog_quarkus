package com.biliwind.blog.service.search;

import com.biliwind.blog.model.BlogRegion;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostSearchQueryTest {

    @Test
    void normalizesTypeSortAndDateOnceForBothAdapters() {
        PostSearchQuery query = PostSearchQuery.of(
                "  title:Quarkus hello  ", "post", "newest", "7d", "en-us", BlogRegion.CN,
                3, 10, true);

        assertEquals("title:Quarkus hello", query.rawKeyword());
        assertEquals("post", query.type());
        assertEquals("", query.sort());
        assertEquals("7d", query.date());
        assertTrue(query.publishedAfter() != null);
        assertEquals("Quarkus", query.parsed().title());
        assertEquals("hello", query.parsed().freeText());
        assertEquals(BlogRegion.CN, query.region());
        assertEquals(20, query.offset());
        assertEquals(100, query.candidateLimit());
        assertTrue(query.supportsElasticsearch());
        assertFalse(query.isTaxonomy());
    }

    @Test
    void dateFilterBecomesOneThresholdSharedByAdapters() {
        OffsetDateTime now = OffsetDateTime.parse("2026-05-10T00:00:00Z");

        OffsetDateTime sevenDays = PostSearchQuery.dateThreshold("7d", now);
        OffsetDateTime thirtyDays = PostSearchQuery.dateThreshold("30d", now);
        OffsetDateTime year = PostSearchQuery.dateThreshold("365d", now);

        assertEquals(now.minusDays(7), sevenDays);
        assertEquals(now.minusDays(30), thirtyDays);
        assertEquals(now.minusDays(365), year);
        assertNull(PostSearchQuery.dateThreshold("", now));
    }

    @Test
    void candidateLimitGrowsWithPageButStaysBounded() {
        PostSearchQuery first = PostSearchQuery.of("q", "all", "", "", "zh-cn", BlogRegion.GLOBAL,
                1, 10, true);
        PostSearchQuery deep = PostSearchQuery.of("q", "all", "", "", "zh-cn", BlogRegion.GLOBAL,
                500, 10, true);

        assertEquals(100, first.candidateLimit());
        assertEquals(1000, deep.candidateLimit());
    }
}
