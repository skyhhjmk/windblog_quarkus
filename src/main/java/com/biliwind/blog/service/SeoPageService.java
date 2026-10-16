package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import io.quarkus.qute.TemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class SeoPageService {
    @Inject CanonicalUrlService canonical;

    public List<Alternate> alternates(String path) {
        String plain = path.replaceFirst("^/(?i:zh-cn|en-us)(?=/|$)", "");
        plain = plain.replace("/amp/post/", "/post/");
        if (!plain.startsWith("/post/")) return List.of();
        String slug = java.net.URLDecoder.decode(plain.substring(6), java.nio.charset.StandardCharsets.UTF_8);
        Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null and visibility = 0 and publishedRevision is not null",
                slug, PostStatus.PUBLISHED).firstResult();
        if (post == null) return List.of();
        com.biliwind.blog.model.SeoPostPolicy policy = com.biliwind.blog.model.SeoPostPolicy.find("postId", post.id).firstResult();
        if (policy != null && policy.noindex) return List.of();
        List<Alternate> result = new ArrayList<>();
        for (String lang : LanguageConstant.SUPPORT_LANGS.stream().sorted().toList()) {
            if (!has(post.publishedRevision.title, lang) || !has(post.publishedRevision.contentMarkdown, lang)) continue;
            String prefix = LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(lang) ? "" : "/" + lang;
            result.add(new Alternate(lang, canonical.buildPath(prefix + plain)));
        }
        result.stream().filter(a -> LanguageConstant.DEFAULT_LANG.equalsIgnoreCase(a.language())).findFirst()
                .ifPresent(a -> result.add(new Alternate("x-default", a.url())));
        return result;
    }

    private boolean has(Map<String, String> map, String lang) {
        return map != null && map.entrySet().stream().anyMatch(e -> lang.equalsIgnoreCase(e.getKey())
                && e.getValue() != null && !e.getValue().isBlank());
    }

    @TemplateData
    public record Alternate(String language, String url) {}
}
