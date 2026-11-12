package com.biliwind.blog.service.search;

import com.biliwind.blog.common.helper.SearchQueryParser;
import com.biliwind.blog.model.BlogRegion;

import java.time.OffsetDateTime;

/**
 * The one description of a reader's search intent. Adapters receive this
 * object, never the raw HTTP parameters, so type/sort/date/parsed-filter
 * decisions are made once before any backend is chosen.
 */
public record PostSearchQuery(
        String rawKeyword,
        SearchQueryParser parsed,
        String type,
        String sort,
        String date,
        OffsetDateTime publishedAfter,
        String lang,
        BlogRegion region,
        int page,
        int pageSize,
        boolean elasticsearchAllowed
) {

    public static final int MAX_CANDIDATES = 1000;

    public static PostSearchQuery of(String keyword,
                                     String type,
                                     String sort,
                                     String date,
                                     String lang,
                                     BlogRegion region,
                                     int page,
                                     int pageSize,
                                     boolean elasticsearchAllowed) {
        String rawKeyword = keyword == null ? "" : keyword.trim();
        String normalizedDate = normalizeDate(date);
        return new PostSearchQuery(
                rawKeyword,
                SearchQueryParser.parse(rawKeyword),
                normalizeType(type),
                normalizeSort(sort),
                normalizedDate,
                dateThreshold(normalizedDate, OffsetDateTime.now()),
                lang == null || lang.isBlank() ? "zh-cn" : lang,
                region == null ? BlogRegion.GLOBAL : region,
                Math.max(1, page),
                Math.max(1, pageSize),
                elasticsearchAllowed);
    }

    public int offset() {
        return (page - 1) * pageSize;
    }

    /** Bounded candidate set used when two sources must be merged before paging. */
    public int candidateLimit() {
        return Math.min(MAX_CANDIDATES, Math.max(100, page * pageSize));
    }

    public boolean hasKeyword() {
        return !rawKeyword.isBlank();
    }

    public boolean supportsElasticsearch() {
        return "all".equals(type) || "post".equals(type);
    }

    public boolean isTaxonomy() {
        return "tag".equals(type) || "category".equals(type);
    }

    public String highlightText() {
        if (!parsed.freeText().isBlank()) {
            return parsed.freeText();
        }
        if (parsed.title() != null) {
            return parsed.title();
        }
        if (parsed.content() != null) {
            return parsed.content();
        }
        if (parsed.author() != null) {
            return parsed.author();
        }
        return parsed.user() == null ? "" : parsed.user();
    }

    static OffsetDateTime dateThreshold(String date, OffsetDateTime now) {
        return switch (date) {
            case "7d" -> now.minusDays(7);
            case "30d" -> now.minusDays(30);
            case "365d" -> now.minusDays(365);
            default -> null;
        };
    }

    private static String normalizeType(String type) {
        if ("post".equals(type) || "tag".equals(type) || "category".equals(type)) {
            return type;
        }
        return "all";
    }

    private static String normalizeSort(String sort) {
        if ("latest".equals(sort) || "hot".equals(sort)) {
            return sort;
        }
        return "";
    }

    private static String normalizeDate(String date) {
        if ("7d".equals(date) || "30d".equals(date) || "365d".equals(date)) {
            return date;
        }
        return "";
    }
}
