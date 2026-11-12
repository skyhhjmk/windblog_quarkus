package com.biliwind.blog.service.search;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One ordering rule for every search adapter. Plain relevance orders by weight,
 * mixed content types by type, and the explicit sorts by recency or popularity.
 */
public final class SearchHitComparator {

    private static final Comparator<SearchHit> BY_DATE_DESC = Comparator
            .comparing((SearchHit hit) -> hit.date(), Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed();

    private static final Comparator<SearchHit> BY_TYPE_WEIGHT = Comparator.comparingInt(hit -> switch (hit.type()) {
        case "post" -> 0;
        case "tag" -> 1;
        case "category" -> 2;
        default -> 3;
    });

    private static final Comparator<SearchHit> BY_TITLE =
            Comparator.comparing(hit -> hit.title().toLowerCase(Locale.ROOT));

    private static final Comparator<SearchHit> BY_WEIGHT_DESC = Comparator
            .comparingInt((SearchHit hit) -> hit.weight())
            .reversed();

    private SearchHitComparator() {
    }

    public static Comparator<SearchHit> forSort(String sort) {
        if ("hot".equals(sort)) {
            Comparator<SearchHit> byViewCount = Comparator
                    .comparingLong((SearchHit hit) -> hit.viewCount())
                    .reversed();
            return byViewCount.thenComparing(BY_WEIGHT_DESC).thenComparing(BY_DATE_DESC).thenComparing(BY_TITLE);
        }
        if ("latest".equals(sort)) {
            return BY_DATE_DESC.thenComparing(BY_WEIGHT_DESC).thenComparing(BY_TYPE_WEIGHT).thenComparing(BY_TITLE);
        }
        return BY_WEIGHT_DESC.thenComparing(BY_TYPE_WEIGHT).thenComparing(BY_DATE_DESC).thenComparing(BY_TITLE);
    }

    public static List<SearchHit> page(List<SearchHit> hits, int offset, int pageSize) {
        int fromIndex = Math.min(hits.size(), Math.max(0, offset));
        int toIndex = Math.min(hits.size(), fromIndex + pageSize);
        return hits.subList(fromIndex, toIndex);
    }
}
