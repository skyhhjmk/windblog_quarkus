package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Language alternates for the current page. Articles only advertise the
 * languages that actually have a title and body in the published revision, so
 * hreflang, x-default and the sitemap never point crawlers at a fallback copy
 * rendered in another language. Other public pages are selectable in every
 * supported language and advertise all of them.
 */
@ApplicationScoped
public class SeoPageService {

    @Inject
    CanonicalUrlService canonical;

    public List<Alternate> alternates(String path) {
        String plain = normalizePath(path);
        if (plain == null) {
            return List.of();
        }
        if (plain.startsWith("/post/")) {
            String slug = java.net.URLDecoder.decode(plain.substring(6),
                    java.nio.charset.StandardCharsets.UTF_8);
            Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                    slug, PostStatus.PUBLISHED).firstResult();
            if (post == null) {
                return List.of();
            }
            com.biliwind.blog.model.SeoPostPolicy policy =
                    com.biliwind.blog.model.SeoPostPolicy.find("postId", post.id).firstResult();
            if (policy != null && policy.noindex) {
                return List.of();
            }
            return alternatesForLanguages(plain, availableLanguages(post.publishedRevision));
        }
        return buildAlternates(plain, new LinkedHashSet<>(LanguageConstant.SUPPORTED_LANG_ORDER));
    }

    /**
     * Builds alternates for callers that already know which languages exist,
     * for example the sitemap, which would otherwise load every post again.
     */
    public List<Alternate> alternatesForLanguages(String path, Collection<String> languages) {
        String plain = normalizePath(path);
        if (plain == null) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        if (languages != null) {
            for (String language : languages) {
                String value = LanguageHelper.normalizeToSupportedLang(language);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        return buildAlternates(plain, normalized);
    }

    private String normalizePath(String path) {
        String plain = LanguageHelper.stripLanguagePrefix(path == null ? "/" : path);
        plain = plain.replace("/amp/post/", "/post/");
        if (plain.isBlank()) {
            plain = "/";
        }
        // Legal pages carry one configured document for every language; a
        // translation alternate would advertise content that does not exist.
        if (plain.equals("/terms") || plain.equals("/privacy")
                || plain.startsWith("/api/") || plain.startsWith("/user") || plain.startsWith("/search")
                || plain.startsWith("/r/") || plain.startsWith("/uploads/")
                || plain.startsWith("/link/go/") || plain.startsWith("/q/")) {
            return null;
        }
        return plain;
    }

    private List<Alternate> buildAlternates(String plain, Set<String> availableLanguages) {
        if (availableLanguages.isEmpty()) {
            return List.of();
        }
        String defaultLanguage = canonical.getDefaultLanguage();
        List<Alternate> result = new ArrayList<>();
        for (String lang : LanguageConstant.SUPPORTED_LANG_ORDER) {
            if (!availableLanguages.contains(lang)) {
                continue;
            }
            result.add(new Alternate(lang, canonical.buildPath(
                    LanguageHelper.localizedPath(plain, lang, defaultLanguage))));
        }
        String xDefault = availableLanguages.contains(defaultLanguage)
                ? defaultLanguage
                : result.getFirst().language();
        result.add(new Alternate("x-default", canonical.buildPath(
                LanguageHelper.localizedPath(plain, xDefault, defaultLanguage))));
        return result;
    }

    private Set<String> availableLanguages(PostRevision revision) {
        Set<String> available = new LinkedHashSet<>();
        if (revision == null) {
            return available;
        }
        for (String lang : LanguageConstant.SUPPORTED_LANG_ORDER) {
            if (LanguageHelper.hasLocalizedValue(revision.title, lang)
                    && LanguageHelper.hasLocalizedValue(revision.contentMarkdown, lang)) {
                available.add(lang);
            }
        }
        return available;
    }

    @TemplateData
    public record Alternate(String language, String url) {
    }
}
