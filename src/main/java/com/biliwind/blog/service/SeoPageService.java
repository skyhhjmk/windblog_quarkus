package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;

/**
 * Language alternates for the current page. Page language is selectable for
 * every public page and no longer depends on whether an article has a
 * translation, so both supported languages are always advertised with the
 * region's default language forming the x-default entry.
 */
@ApplicationScoped
public class SeoPageService {

    @Inject
    CanonicalUrlService canonical;

    @Inject
    RegionContext regionContext;

    @Inject
    LanguageSettingsService languageSettingsService;

    public List<Alternate> alternates(String path) {
        String plain = LanguageHelper.stripLanguagePrefix(path == null ? "/" : path);
        plain = plain.replace("/amp/post/", "/post/");
        if (plain.isBlank()) {
            plain = "/";
        }
        if (plain.startsWith("/api/") || plain.startsWith("/user") || plain.startsWith("/search")
                || plain.startsWith("/r/") || plain.startsWith("/uploads/")
                || plain.startsWith("/link/go/") || plain.startsWith("/q/")) {
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
        }

        String defaultLanguage = languageSettingsService.defaultLanguage(regionContext.getCurrentRegion());
        List<Alternate> result = new ArrayList<>();
        for (String lang : LanguageConstant.SUPPORTED_LANG_ORDER) {
            result.add(new Alternate(lang, canonical.buildPath(
                    LanguageHelper.localizedPath(plain, lang, defaultLanguage))));
        }
        result.add(new Alternate("x-default", canonical.buildPath(
                LanguageHelper.localizedPath(plain, defaultLanguage, defaultLanguage))));
        return result;
    }

    @TemplateData
    public record Alternate(String language, String url) {
    }
}
