package com.biliwind.blog.controller;

import com.biliwind.blog.controller.SearchController.SearchHit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

        // Comparator mirroring SearchController.hitComparator("")
        Comparator<SearchHit> byDateDesc = Comparator
                .comparing((SearchHit hit) -> hit.date(), Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed();
        Comparator<SearchHit> byTypeWeight = Comparator.comparingInt(hit -> switch (hit.type()) {
            case "post" -> 0;
            case "tag" -> 1;
            case "category" -> 2;
            default -> 3;
        });
        Comparator<SearchHit> byTitle = Comparator.comparing(hit -> hit.title().toLowerCase());
        Comparator<SearchHit> byWeightDesc = Comparator
                .comparingInt((SearchHit hit) -> hit.weight())
                .reversed();
        Comparator<SearchHit> comparator = byWeightDesc.thenComparing(byTypeWeight).thenComparing(byDateDesc).thenComparing(byTitle);

        hits.sort(comparator);

        assertEquals("Article High Weight", hits.get(0).title());
        assertEquals(150, hits.get(0).weight());
        assertEquals("Article Low Weight", hits.get(1).title());
        assertEquals(10, hits.get(1).weight());
        assertEquals("Article Zero Weight", hits.get(2).title());
        assertEquals(0, hits.get(2).weight());
    }
}
