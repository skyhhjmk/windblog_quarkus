package com.biliwind.blog.service.search;

import com.biliwind.blog.common.dto.SearchHighlightSegment;
import com.biliwind.blog.controller.IndexController;
import io.quarkus.qute.TemplateData;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.OffsetDateTime;
import java.util.List;

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
