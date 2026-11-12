package com.biliwind.blog.controller;

import com.biliwind.blog.service.search.SearchHit;
import com.biliwind.blog.service.search.SearchHitComparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchHitSortingTest {

    private SearchHit createHit(String type, String title, int weight, OffsetDateTime date, long viewCount) {
        return new SearchHit(
                type,
                type,
                title,
                "summary",
                "aiSummary",
                "/post/" + title,
                title,
                date,
                "2026-01-01",
                "meta",
                "[Read More]",
                "Category",
                "category",
                viewCount,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Author",
                weight
        );
    }

    @Test
    @DisplayName("默认排序优先按权重降序排列，同权重按类型与日期")
    void shouldSortByDefaultWeight() {
        OffsetDateTime now = OffsetDateTime.now();
        SearchHit hitLowWeight = createHit("post", "Article Low Weight", 10, now.minusDays(1), 100);
        SearchHit hitHighWeight = createHit("post", "Article High Weight", 150, now.minusDays(5), 50);
        SearchHit hitZeroWeight = createHit("post", "Article Zero Weight", 0, now, 200);

        List<SearchHit> hits = new ArrayList<>(List.of(hitLowWeight, hitZeroWeight, hitHighWeight));

        hits.sort(SearchHitComparator.forSort(""));

        assertEquals("Article High Weight", hits.get(0).title());
        assertEquals(150, hits.get(0).weight());
        assertEquals("Article Low Weight", hits.get(1).title());
        assertEquals(10, hits.get(1).weight());
        assertEquals("Article Zero Weight", hits.get(2).title());
        assertEquals(0, hits.get(2).weight());
    }
}
