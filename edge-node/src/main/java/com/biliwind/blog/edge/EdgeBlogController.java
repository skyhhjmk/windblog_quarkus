package com.biliwind.blog.edge;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.service.PostAccessService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class EdgeBlogController {
    private static final Logger log = LoggerFactory.getLogger(EdgeBlogController.class);

    @Inject
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;


    @Inject
    LanguageContext languageContext;

    @Inject
    PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "edge.node.region", defaultValue = "global")
    String region;


    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance post(@PathParam("slug") String slug,
                                 @QueryParam("levels") List<Short> levels,
                                 @Context HttpHeaders httpHeaders) {
        return renderPost(slug, null, levels, httpHeaders);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance postWithLang(@PathParam("langCode") String langCode,
                                         @PathParam("slug") String slug,
                                         @QueryParam("levels") List<Short> levels,
                                         @Context HttpHeaders httpHeaders) {
        return renderPost(slug, langCode, levels, httpHeaders);
    }

    private TemplateInstance renderPost(String slug, String langCode, List<Short> levels, HttpHeaders httpHeaders) {
        resolveLanguage(langCode);
        String resolvedLang = languageContext.getLang();

        if (slug.endsWith(".html")) {
            slug = slug.substring(0, slug.length() - 5);
        }

        Post postEntity = Post.find("slug = ?1 and deletedAt is null and (visibilityRegions is null or cast(visibilityRegions as String) like ?2)",
                slug, "%\"" + region + "\"%").firstResult();

        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        String localizedTitle = LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);
        String rawContent = "";
        if (postEntity.currentRevision != null) {
            rawContent = LanguageHelper.resolveLocalizedValue(postEntity.currentRevision.contentMarkdown, resolvedLang);
        }

        Long currentUserId = resolveUserIdFromCookie(httpHeaders);
        long postPrice = postAccessService.getPostPrice(postEntity);

        boolean isAuthor = currentUserId != null && postEntity.user != null && currentUserId.equals(postEntity.user.id);

        String processedContent = postAccessService.getCachedPreviewContent(postEntity, resolvedLang, rawContent, postPrice);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? postContentTemplate : postTemplate;

        return template
                .data("language", resolvedLang)
                .data("postId", postEntity.id)
                .data("postSlug", slug)
                .data("postTitle", localizedTitle == null ? slug : localizedTitle)
                .data("postBody", MarkdownHelper.toHtml(processedContent))
                .data("publishedAt", postEntity.publishedAt)
                .data("authorName", postEntity.user != null ? postEntity.user.username : "Unknown")
                .data("postCategory", postEntity.category != null ? LanguageHelper.resolveLocalizedValue(postEntity.category.name, resolvedLang) : "未分类")
                .data("postCategorySlug", postEntity.category != null ? postEntity.category.slug : "uncategorized")
                .data("postPrice", postPrice);
    }

    private void resolveLanguage(String langCode) {
        if (langCode == null || langCode.isBlank()) {
            languageContext.setLang(LanguageConstant.DEFAULT_LANG);
            return;
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(langCode);
        languageContext.setLang(normalized == null ? LanguageConstant.DEFAULT_LANG : normalized);
    }

    private Long resolveUserIdFromCookie(HttpHeaders headers) {
        var cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null) return null;
        var verified = tokenVerifier.verify(cookie.getValue());
        return verified != null ? verified.uid() : null;
    }
}
