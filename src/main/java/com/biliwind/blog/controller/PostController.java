package com.biliwind.blog.controller;

import com.biliwind.blog.common.annotation.PasswordProtected;
import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PostController {

    @Inject
    @Location("blog/post.html")
    Template postTemplate;

    @Inject
    @Location("blog/post.content.html")
    Template postContentTemplate;

    @Inject
    LanguageContext languageContext;

    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance post(@PathParam("slug") String slug,
                                 @Context HttpHeaders httpHeaders) {
        return render(slug, null, httpHeaders);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    @PasswordProtected
    public TemplateInstance postWithLang(@PathParam("langCode") String langCode,
                                         @PathParam("slug") String slug,
                                         @Context HttpHeaders httpHeaders) {
        return render(slug, langCode, httpHeaders);
    }

    private TemplateInstance render(String slug,
                                    String langCode,
                                    HttpHeaders httpHeaders) {

        resolveLanguage(langCode);

        slug = normalizeSlug(slug);

        Post postEntity = Post.find("slug = ?1 and deletedAt is null", slug)
                .firstResult();

        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        if (postEntity.visibility == 1) {
            throw new NotFoundException("Post is private");
        }

        String resolvedLang = languageContext.getLang();
        String localizedTitle =
                LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);

        String localizedContent =
                resolveContent(postEntity.currentRevision, resolvedLang);

        PostBodyView postBody =
                resolvePostBody(postEntity.renderType, localizedContent);

        String localizedAiSummary =
                LanguageHelper.resolveLocalizedValue(postEntity.aiSummary, resolvedLang);

        Template template =
                PjaxHelper.isPjaxRequest(httpHeaders)
                        ? postContentTemplate
                        : postTemplate;

        return template
                .data("language", resolvedLang)
                .data("postSlug", slug)
                .data("postTitle", localizedTitle == null ? slug : localizedTitle)
                .data("aiSummary", localizedAiSummary)
                .data("aiSummaryStatus", postEntity.aiSummaryStatus != null ? postEntity.aiSummaryStatus.intValue() : 0)
                .data("postBody", postBody.body())
                .data("postBodyHtml", postBody.html())
                .data("publishedAt", postEntity.publishedAt)
                .data("postRenderType", postBody.renderType() != null ? postBody.renderType().name() : null)
                .data("postBodyJson", escapeJavaScript(postBody.body()));
    }

    private String escapeJavaScript(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("'", "\\'");
    }

    private void resolveLanguage(String langCode) {

        if (langCode == null || langCode.isBlank()) {
            languageContext.setLang(LanguageConstant.DEFAULT_LANG);
            return;
        }

        String normalized =
                LanguageHelper.normalizeToSupportedLang(langCode);

        languageContext.setLang(
                normalized == null
                        ? LanguageConstant.DEFAULT_LANG
                        : normalized
        );
    }

    private String normalizeSlug(String slug) {

        if (slug != null && slug.toLowerCase().endsWith(".html")) {
            slug = slug.substring(0, slug.length() - 5);
        }

        if (slug == null || slug.isBlank()) {
            return "untitled";
        }

        return slug;
    }

    private String resolveContent(PostRevision revision,
                                  String lang) {

        if (revision == null) {
            return null;
        }

        return LanguageHelper
                .resolveLocalizedValue(revision.contentMarkdown, lang);
    }

    private PostBodyView resolvePostBody(PostRenderType renderType,
                                         String content) {

        if (content == null || content.isBlank()) {
            return new PostBodyView("", false, null);
        }

        PostRenderType effective =
                renderType == null
                        ? PostRenderType.MARKDOWN
                        : renderType;

        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS ->
                    new PostBodyView(MarkdownHelper.toHtml(content), true, effective);
            case VDITOR ->
                    new PostBodyView(content, false, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL ->
                    new PostBodyView(content, true, effective);
        };
    }

    private record PostBodyView(String body, boolean html, PostRenderType renderType) {}
}
