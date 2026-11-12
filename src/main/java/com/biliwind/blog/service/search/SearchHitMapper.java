package com.biliwind.blog.service.search;

import com.biliwind.blog.common.dto.SearchHighlightSegment;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.PostAuthorHelper;
import com.biliwind.blog.common.helper.SearchContentHelper;
import com.biliwind.blog.common.helper.SearchWeightHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.controller.IndexController;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.PostTag;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns backend rows into the single display shape the search template consumes.
 * Both adapters call this so a post looks the same whether it came from ES or SQL.
 */
@ApplicationScoped
public class SearchHitMapper {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @PersistenceContext
    EntityManager entityManager;

    @Inject
    LanguageContext languageContext;

    public Map<Long, List<IndexController.TagItem>> loadTags(List<Post> posts, String lang) {
        Map<Long, List<IndexController.TagItem>> tagsByPost = new HashMap<>();
        List<Long> postIds = new ArrayList<>();
        for (Post post : posts) {
            if (post.id != null) {
                postIds.add(post.id);
                tagsByPost.put(post.id, new ArrayList<>());
            }
        }
        if (postIds.isEmpty()) {
            return tagsByPost;
        }
        List<PostTag> postTags = entityManager.createQuery(
                        "select postTag from PostTag postTag join fetch postTag.tag where postTag.post.id in :postIds",
                        PostTag.class)
                .setParameter("postIds", postIds)
                .getResultList();
        for (PostTag postTag : postTags) {
            List<IndexController.TagItem> tags = tagsByPost.get(postTag.post.id);
            if (tags != null) {
                tags.add(new IndexController.TagItem(
                        LanguageHelper.resolveLocalizedValue(postTag.tag.name, lang), postTag.tag.slug));
            }
        }
        return tagsByPost;
    }

    public SearchHit postHit(Post post, String lang,
                             Map<Long, List<IndexController.TagItem>> tagsByPost,
                             String keyword) {
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);

        if (title == null || title.isBlank()) {
            title = post.slug;
        }
        if (summary == null || summary.isBlank()) {
            summary = "No summary available.";
        }

        String categoryName = "未分类";
        String categorySlug = "uncategorized";
        if (post.category != null) {
            categoryName = LanguageHelper.resolveLocalizedValue(post.category.name, lang);
            categorySlug = post.category.slug;
        }
        List<IndexController.CategoryItem> categories = post.categories.stream()
                .filter(category -> category != null && category.enabled)
                .map(category -> new IndexController.CategoryItem(
                        LanguageHelper.resolveLocalizedValue(category.name, lang), category.slug))
                .toList();
        if (categories.isEmpty() && post.category != null && post.category.enabled) {
            categories = List.of(new IndexController.CategoryItem(categoryName, categorySlug));
        }

        List<IndexController.TagItem> tags = tagsByPost.getOrDefault(post.id, List.of());

        OffsetDateTime displayDate = effectiveDate(post);
        long viewCount = post.viewCount == null ? 0 : post.viewCount;
        String content = post.publishedRevision == null || post.publishedRevision.contentMarkdown == null
                ? ""
                : LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
        String searchableText = SearchContentHelper.toSearchableText(content, post.renderType);
        List<List<SearchHighlightSegment>> contentSnippets = fallbackContentSnippets(searchableText, keyword);

        Map<String, String> titles = post.title != null ? post.title : Map.of();
        Map<String, String> contentsMap = contentsMap(post);
        int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contentsMap, keyword, lang);
        return new SearchHit(
                "post",
                "Post",
                title,
                summary,
                LanguageHelper.resolveLocalizedValue(post.aiSummary, lang),
                languageContext.localize("/post/" + post.slug),
                post.slug,
                displayDate,
                formatDate(displayDate),
                "slug: " + post.slug,
                "[Read More]",
                categoryName,
                categorySlug,
                viewCount,
                tags,
                categories,
                fallbackHighlight(title, keyword),
                fallbackHighlight(summary, keyword),
                contentSnippets,
                PostAuthorHelper.displayName(post),
                weight
        );
    }

    public SearchHit indexedPostHit(Post dbPost,
                                    ElasticsearchPostSearchService.SearchedPost post,
                                    String lang,
                                    String keyword) {
        OffsetDateTime publishedAt = resolveSearchDate(post, dbPost);

        Map<String, String> titles = (dbPost != null && dbPost.title != null && !dbPost.title.isEmpty())
                ? dbPost.title
                : post.titles();

        Map<String, String> summaries = (dbPost != null && dbPost.summary != null && !dbPost.summary.isEmpty())
                ? dbPost.summary
                : post.summaries();

        Map<String, String> aiSummaries = (dbPost != null && dbPost.aiSummary != null && !dbPost.aiSummary.isEmpty())
                ? dbPost.aiSummary
                : post.aiSummaries();

        Map<String, String> contents = post.contents();
        if (dbPost != null && dbPost.publishedRevision != null && dbPost.publishedRevision.contentMarkdown != null) {
            Map<String, String> dbContents = contentsMap(dbPost);
            if (!dbContents.isEmpty()) {
                contents = dbContents;
            }
        }

        String displayTitle = LanguageHelper.resolveLocalizedValue(titles, lang);
        if (displayTitle == null || displayTitle.isBlank()) {
            displayTitle = post.title(lang);
        }
        if (displayTitle == null || displayTitle.isBlank()) {
            displayTitle = post.slug();
        }

        String displaySummary = LanguageHelper.resolveLocalizedValue(summaries, lang);
        if (displaySummary == null || displaySummary.isBlank()) {
            displaySummary = post.summary(lang);
        }
        if (displaySummary == null || displaySummary.isBlank()) {
            displaySummary = "No summary available.";
        }

        String displayAiSummary = LanguageHelper.resolveLocalizedValue(aiSummaries, lang);
        if (displayAiSummary == null || displayAiSummary.isBlank()) {
            displayAiSummary = post.aiSummary(lang);
        }

        String displayCategoryName = (dbPost != null && dbPost.category != null && dbPost.category.name != null)
                ? LanguageHelper.resolveLocalizedValue(dbPost.category.name, lang)
                : post.categoryName();

        List<SearchHighlightSegment> titleSegments;
        if (displayTitle.equals(post.title()) && post.titleSegments() != null && !post.titleSegments().isEmpty()) {
            titleSegments = post.titleSegments();
        } else {
            titleSegments = fallbackHighlight(displayTitle, keyword);
        }

        List<SearchHighlightSegment> summarySegments;
        if (displaySummary.equals(post.summary()) && post.summarySegments() != null && !post.summarySegments().isEmpty()) {
            summarySegments = post.summarySegments();
        } else {
            summarySegments = fallbackHighlight(displaySummary, keyword);
        }

        int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contents, keyword, lang);

        return new SearchHit(
                "post",
                "Post",
                displayTitle,
                displaySummary,
                displayAiSummary,
                languageContext.localize("/post/" + post.slug()),
                post.slug(),
                publishedAt,
                publishedAt != null ? formatDate(publishedAt) : "unknown",
                "author: " + post.authorName() + " | views: " + post.viewCount(),
                "[Read More]",
                displayCategoryName,
                post.categorySlug(),
                post.viewCount(),
                List.of(),
                post.categories().stream().map(category -> new IndexController.CategoryItem(
                        category.name(), category.slug())).toList(),
                titleSegments,
                summarySegments,
                post.contentSnippets(),
                resolveSearchAuthorName(post.authorId(), post.authorName()),
                weight
        );
    }

    public SearchHit tagHit(Tag tag, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(tag.name, lang);
        String description = LanguageHelper.resolveLocalizedValue(tag.description, lang);
        if (name == null || name.isBlank()) {
            name = tag.slug;
        }
        if (description == null || description.isBlank()) {
            description = "Tag archive.";
        }

        long postCount = PostTag.count("tag.id = ?1 and post.status = ?2 and post.deletedAt is null "
                + "and post.visibility = 0 and post.publishedRevision is not null",
                tag.id, PostStatus.PUBLISHED);
        return new SearchHit(
                "tag",
                "Tag",
                name,
                description,
                null,
                "/tag/" + tag.slug,
                tag.slug,
                tag.createdAt,
                formatDate(tag.createdAt),
                "posts: " + postCount + " | slug: " + tag.slug,
                "[Browse Tag]",
                null,
                null,
                0,
                List.of(),
                List.of()
        );
    }

    public SearchHit categoryHit(Category category, String lang) {
        String name = LanguageHelper.resolveLocalizedValue(category.name, lang);
        String description = LanguageHelper.resolveLocalizedValue(category.description, lang);
        if (name == null || name.isBlank()) {
            name = category.slug;
        }
        if (description == null || description.isBlank()) {
            description = "Category archive.";
        }

        long childCount = Category.count("parent.id", category.id);
        return new SearchHit(
                "category",
                "Category",
                name,
                description,
                null,
                "/category/" + category.slug,
                category.slug,
                category.createdAt,
                formatDate(category.createdAt),
                "children: " + childCount + " | path: " + (category.path == null ? "" : category.path),
                "[Browse Category]",
                null,
                null,
                0,
                List.of(),
                List.of()
        );
    }

    public static OffsetDateTime effectiveDate(Post post) {
        return post.publishedAt != null ? post.publishedAt : post.createdAt;
    }

    public static String formatDate(OffsetDateTime date) {
        return date == null ? "unknown" : DATE_FORMATTER.format(date);
    }

    private OffsetDateTime resolveSearchDate(ElasticsearchPostSearchService.SearchedPost post, Post dbPost) {
        OffsetDateTime date = parseSearchPublishedAt(post.publishedAt());
        if (date == null) {
            date = parseSearchPublishedAt(post.createdAt());
        }
        if (date == null && dbPost != null) {
            date = effectiveDate(dbPost);
        }
        return date;
    }

    private OffsetDateTime parseSearchPublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeException exception) {
            try {
                return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
            } catch (DateTimeException ignored) {
                try {
                    return LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC);
                } catch (DateTimeException invalidDate) {
                    return null;
                }
            }
        }
    }

    private String resolveSearchAuthorName(Long authorId, String indexedAuthorName) {
        if (indexedAuthorName != null && !indexedAuthorName.isBlank()
                && !"unknown".equalsIgnoreCase(indexedAuthorName.trim())) {
            return indexedAuthorName.trim();
        }
        if (authorId != null && authorId > 0) {
            User user = entityManager.find(User.class, authorId);
            if (user != null && user.username != null && !user.username.isBlank()) {
                return user.username.trim();
            }
        }
        return "Unknown";
    }

    private Map<String, String> contentsMap(Post post) {
        Map<String, String> contents = new HashMap<>();
        if (post.publishedRevision != null && post.publishedRevision.contentMarkdown != null) {
            for (Map.Entry<String, String> entry : post.publishedRevision.contentMarkdown.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    contents.put(entry.getKey(), SearchContentHelper.toSearchableText(entry.getValue(), post.renderType));
                }
            }
        }
        return contents;
    }

    private List<SearchHighlightSegment> fallbackHighlight(String value, String keyword) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        List<String> terms = SearchWeightHelper.extractTerms(keyword);
        if (terms.isEmpty()) {
            return List.of(new SearchHighlightSegment(value, false));
        }

        List<SearchHighlightSegment> segments = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int matchStart = -1;
            String matchedTerm = null;
            for (String term : terms) {
                int candidate = value.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT), cursor);
                if (candidate >= 0 && (matchStart < 0 || candidate < matchStart
                        || (candidate == matchStart && term.length() > matchedTerm.length()))) {
                    matchStart = candidate;
                    matchedTerm = term;
                }
            }
            if (matchStart < 0) {
                addPlainSegment(segments, value.substring(cursor));
                break;
            }
            addPlainSegment(segments, value.substring(cursor, matchStart));
            segments.add(new SearchHighlightSegment(
                    value.substring(matchStart, matchStart + matchedTerm.length()), true));
            cursor = matchStart + matchedTerm.length();
        }
        return segments.isEmpty() ? List.of(new SearchHighlightSegment(value, false)) : segments;
    }

    private List<List<SearchHighlightSegment>> fallbackContentSnippets(String content, String keyword) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> terms = SearchWeightHelper.extractTerms(keyword);
        if (terms.isEmpty()) {
            return List.of();
        }
        int matchStart = -1;
        String matchedTerm = null;
        String foldedContent = content.toLowerCase(Locale.ROOT);
        for (String term : terms) {
            int candidate = foldedContent.indexOf(term.toLowerCase(Locale.ROOT));
            if (candidate >= 0 && (matchStart < 0 || candidate < matchStart
                    || (candidate == matchStart && term.length() > matchedTerm.length()))) {
                matchStart = candidate;
                matchedTerm = term;
            }
        }
        if (matchStart < 0) {
            return List.of();
        }

        int start = Math.max(0, matchStart - 90);
        int end = Math.min(content.length(), matchStart + matchedTerm.length() + 110);
        String excerpt = content.substring(start, end);
        List<SearchHighlightSegment> segments = new ArrayList<>();
        if (start > 0) {
            segments.add(new SearchHighlightSegment("…", false));
        }
        segments.addAll(fallbackHighlight(excerpt, keyword));
        if (end < content.length()) {
            segments.add(new SearchHighlightSegment("…", false));
        }
        return List.of(segments);
    }

    private void addPlainSegment(List<SearchHighlightSegment> segments, String text) {
        if (!text.isEmpty()) {
            segments.add(new SearchHighlightSegment(text, false));
        }
    }
}
