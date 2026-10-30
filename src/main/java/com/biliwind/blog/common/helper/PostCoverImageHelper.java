package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.service.PublicMediaUrlPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the list-card cover for a post.
 *
 * <p>The explicit featured-image association ({@code post_media.usage_type = 1})
 * wins. Posts without one fall back to the first image referenced by the
 * published article body, so imports and older posts still get a thumbnail.</p>
 */
public final class PostCoverImageHelper {

    private static final Set<String> PREFERRED_MEDIA_METADATA_KEYS =
            new LinkedHashSet<>(List.of("thumbnailUrl", "webpUrl", "previewUrl", "coverUrl"));

    private static final Pattern FENCED_CODE = Pattern.compile(
            "(?:```|~~~)[\\s\\S]*?(?:```|~~~)");
    private static final Pattern INLINE_CODE = Pattern.compile("`+[^`]*`+");
    private static final Pattern HIDDEN_BLOCK = Pattern.compile(
            "\\[\\s*hide-(?:text|attachment)\\b[^\\]]*][\\s\\S]*?\\[/\\s*hide-(?:text|attachment)\\s*]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_IMAGE = Pattern.compile(
            "<img\\b[^>]*?\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile(
            "!\\[[^\\]]*]\\(\\s*<?([^\\s)>]+)>?");
    private static final Pattern HTML_ENTITY = Pattern.compile("&(?:amp|#38);", Pattern.CASE_INSENSITIVE);

    private PostCoverImageHelper() {
    }

    /**
     * Resolves featured-image URLs for a batch of posts in one query.
     * The result is keyed by post id and only contains posts with a usable image.
     */
    public static Map<Long, String> findFeaturedImageUrls(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        List<PostMedia> references = PostMedia.find(
                "select pm from PostMedia pm join fetch pm.media media "
                        + "where pm.post.id in ?1 and pm.usageType = 1 and media.deletedAt is null "
                        + "order by pm.position asc, pm.createdAt asc",
                new ArrayList<>(postIds)).list();
        Map<Long, String> result = new LinkedHashMap<>();
        for (PostMedia reference : references) {
            Long postId = reference.id == null ? null : reference.id.postId;
            if (postId == null || result.containsKey(postId)) {
                continue;
            }
            String url = mediaImageUrl(reference.media);
            if (url != null) {
                result.put(postId, url);
            }
        }
        return result;
    }

    /**
     * Returns the explicit featured image when present, otherwise the first
     * image in the localized article body.
     */
    public static String resolveCoverImage(Post post, String language, String featuredImageUrl) {
        if (featuredImageUrl != null && !featuredImageUrl.isBlank()) {
            return featuredImageUrl;
        }
        if (post == null || post.publishedRevision == null) {
            return null;
        }
        return firstImageUrl(post.publishedRevision.contentMarkdown, language);
    }

    /**
     * Finds the first renderable image in a localized Markdown body. The
     * preferred language is tried first; another translation is used only when
     * it contributes an image the preferred language lacks.
     */
    public static String firstImageUrl(Map<String, String> localizedContent, String language) {
        if (localizedContent == null || localizedContent.isEmpty()) {
            return null;
        }
        Set<String> candidates = new LinkedHashSet<>();
        String normalizedLanguage = LanguageHelper.normalizeToSupportedLang(language);
        if (normalizedLanguage != null) {
            candidates.add(normalizedLanguage);
        } else if (language != null && !language.isBlank()) {
            candidates.add(language);
        }
        candidates.add("zh-cn");
        candidates.add("en-us");
        candidates.addAll(localizedContent.keySet());

        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate == null || !seen.add(candidate.toLowerCase())) {
                continue;
            }
            String content = LanguageHelper.lookupIgnoreCase(localizedContent, candidate);
            String url = firstImageUrl(content);
            if (url != null) {
                return url;
            }
        }
        return null;
    }

    /**
     * Finds the first renderable image URL in a Markdown/HTML fragment,
     * ignoring images inside hidden content and code samples.
     */
    public static String firstImageUrl(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return null;
        }
        String content = HIDDEN_BLOCK.matcher(markdown).replaceAll("");
        content = FENCED_CODE.matcher(content).replaceAll("");
        content = INLINE_CODE.matcher(content).replaceAll("");

        int markdownIndex = -1;
        String markdownUrl = null;
        Matcher markdownMatcher = MARKDOWN_IMAGE.matcher(content);
        while (markdownMatcher.find()) {
            String candidate = usableUrl(markdownMatcher.group(1));
            if (candidate != null) {
                markdownIndex = markdownMatcher.start();
                markdownUrl = candidate;
                break;
            }
        }

        int htmlIndex = -1;
        String htmlUrl = null;
        Matcher htmlMatcher = HTML_IMAGE.matcher(content);
        while (htmlMatcher.find()) {
            String candidate = usableUrl(htmlMatcher.group(1));
            if (candidate != null) {
                htmlIndex = htmlMatcher.start();
                htmlUrl = candidate;
                break;
            }
        }

        if (htmlUrl == null) {
            return markdownUrl;
        }
        if (markdownUrl == null) {
            return htmlUrl;
        }
        return markdownIndex <= htmlIndex ? markdownUrl : htmlUrl;
    }

    private static String mediaImageUrl(Media media) {
        if (media == null || media.mediaType != 0 || media.deletedAt != null) {
            return null;
        }
        if (media.metadata != null) {
            for (String key : PREFERRED_MEDIA_METADATA_KEYS) {
                Object value = media.metadata.get(key);
                if (value instanceof String text) {
                    String candidate = usableUrl(text);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return usableUrl(media.url);
    }

    private static String usableUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String candidate = HTML_ENTITY.matcher(raw.trim()).replaceAll("&");
        if (candidate.isBlank() || !PublicMediaUrlPolicy.isAllowedImageSource(candidate)) {
            return null;
        }
        return candidate;
    }
}
