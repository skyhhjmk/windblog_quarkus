package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.repository.PostRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jsoup.Jsoup;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class FeedService {

    @Inject
    PostRepository postRepository;

    @ConfigProperty(name = "blog.url")
    String baseUrl;

    private static final DateTimeFormatter RFC_822_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final DateTimeFormatter ISO_8601_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    public List<FeedPostView> getPublishedPosts() {
        return postRepository.findAllPublished().stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    public String getBaseUrl() {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    public String getCurrentRfc822Date() {
        return OffsetDateTime.now().format(RFC_822_FORMATTER);
    }

    private FeedPostView toView(Post post) {
        String lang = LanguageConstant.DEFAULT_LANG;
        String title = LanguageHelper.resolveLocalizedValue(post.title, lang);
        if (title == null) title = post.slug;

        String summary = LanguageHelper.resolveLocalizedValue(post.summary, lang);
        if (summary == null || summary.isBlank()) {
            // Try AI summary
            summary = LanguageHelper.resolveLocalizedValue(post.aiSummary, lang);
        }
        if (summary == null || summary.isBlank()) {
            // Extract from content
            String content = LanguageHelper.resolveLocalizedValue(post.publishedRevision.contentMarkdown, lang);
            summary = extractSummary(content);
        }

        return new FeedPostView(
                post.slug,
                title,
                summary,
                post.publishedAt != null ? post.publishedAt.format(RFC_822_FORMATTER) : "",
                formatLastModified(post.updatedAt, post.createdAt),
                post.user != null ? post.user.username : "Admin"
        );
    }

    private String extractSummary(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        // Convert to HTML and strip tags
        String html = MarkdownHelper.toHtml(content);
        String text = Jsoup.parse(html).text();
        if (text.length() > 200) {
            text = text.substring(0, 200) + "...";
        }
        return text;
    }

    public List<SitemapUrlView> getCategorySitemapUrls() {
        return com.biliwind.blog.model.Category.listAll().stream()
                .map(c -> (com.biliwind.blog.model.Category) c)
                .map(c -> new SitemapUrlView(
                        getBaseUrl() + "/category/" + c.slug,
                        formatLastModified(c.updatedAt, c.createdAt)
                ))
                .collect(Collectors.toList());
    }

    public List<SitemapUrlView> getTagSitemapUrls() {
        return com.biliwind.blog.model.Tag.listAll().stream()
                .map(t -> (com.biliwind.blog.model.Tag) t)
                .map(t -> new SitemapUrlView(
                        getBaseUrl() + "/tag/" + t.slug,
                        formatLastModified(t.updatedAt, t.createdAt)
                ))
                .collect(Collectors.toList());
    }

    public List<SitemapUrlView> getPageSitemapUrls() {
        String normalizedBaseUrl = getBaseUrl();
        return List.of(
                new SitemapUrlView(normalizedBaseUrl + "/", ""),
                new SitemapUrlView(normalizedBaseUrl + "/category", ""),
                new SitemapUrlView(normalizedBaseUrl + "/tag", ""),
                new SitemapUrlView(normalizedBaseUrl + "/link", "")
        );
    }

    private String formatLastModified(OffsetDateTime updatedAt, OffsetDateTime createdAt) {
        if (updatedAt != null) {
            return updatedAt.format(ISO_8601_FORMATTER);
        }
        if (createdAt != null) {
            return createdAt.format(ISO_8601_FORMATTER);
        }
        return "";
    }

    public record FeedPostView(
            String slug,
            String title,
            String summary,
            String pubDate,
            String lastModified,
            String author
    ) {}

    public record SitemapUrlView(
            String loc,
            String lastmod
    ) {}
}
