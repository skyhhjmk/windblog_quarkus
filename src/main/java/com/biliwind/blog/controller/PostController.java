package com.biliwind.blog.controller;

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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

@Path("/")
public class PostController {

    @Inject
    @Location("blog/post.html")
    Template post;

    @Inject
    @Location("blog/post.content.html")
    Template postContent;

    @Inject
    LanguageContext languageContext;

    @GET
    @Path("/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance post(@PathParam("slug") String slug,
            @Context HttpHeaders httpHeaders) {
        return render(slug, null, httpHeaders);
    }

    @GET
    @Path("/{langCode}/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance postWithLang(@PathParam("langCode") String langCode,
            @PathParam("slug") String slug,
            @Context HttpHeaders httpHeaders) {
        return render(slug, langCode, httpHeaders);
    }

    private TemplateInstance render(String slug, String langCode, HttpHeaders httpHeaders) {
        if (langCode == null || langCode.isBlank()) {
            languageContext.setLang(LanguageConstant.DEFAULT_LANG);
        } else {
            String normalizedLang = LanguageHelper.normalizeToSupportedLang(langCode);
            languageContext.setLang(normalizedLang == null ? LanguageConstant.DEFAULT_LANG : normalizedLang);
        }

        slug = normalizeSlug(slug);
        Post postEntity = Post.find("slug", slug).firstResult();
        if (postEntity == null) {
            throw new NotFoundException("Post not found: " + slug);
        }

        String resolvedLang = languageContext.getLang();
        String localizedTitle = LanguageHelper.resolveLocalizedValue(postEntity.title, resolvedLang);
        String localizedContent = resolveContent(postEntity.currentRevision, resolvedLang);
        PostBodyView postBody = resolvePostBody(postEntity.renderType, localizedContent);

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? postContent : post;
        return template
                .data("language", resolvedLang)
                .data("postSlug", slug)
                .data("postTitle", localizedTitle == null ? slug : localizedTitle)
                .data("postBody", postBody.body())
                .data("postBodyHtml", postBody.html());
    }

    private String normalizeSlug(String slug) {
        if (slug != null && slug.toLowerCase().endsWith(".html")) {
            int suffixLength = ".html".length();
            if (slug.length() >= suffixLength) {
                slug = slug.substring(0, slug.length() - suffixLength);
            } else {
                slug = "";
            }
        }
        if (slug == null || slug.isBlank()) {
            slug = "untitled";
        }
        return slug;
    }

    private String resolveContent(PostRevision currentRevision, String lang) {
        if (currentRevision == null) {
            return null;
        }
        return LanguageHelper.resolveLocalizedValue(currentRevision.contentMarkdown, lang);
    }

    private PostBodyView resolvePostBody(PostRenderType renderType, String content) {
        if (content == null || content.isBlank()) {
            return new PostBodyView("", false);
        }
        PostRenderType effectiveType = renderType == null ? PostRenderType.MARKDOWN : renderType;
        return switch (effectiveType) {
            case MARKDOWN, VDITOR, FLUTTER_MARKDOWN_PLUS -> new PostBodyView(MarkdownHelper.toHtml(content), true);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL -> new PostBodyView(content, true);
        };
    }

    private record PostBodyView(String body, boolean html) {
    }
}
