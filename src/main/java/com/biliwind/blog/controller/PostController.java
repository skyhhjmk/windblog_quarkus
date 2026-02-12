package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

/**
 * Post controller
 */
@Path("/post/{slug}")
public class PostController {

    @Inject
    @Location("blog/post.html")
    Template post;

    @Inject
    @Location("blog/post.content.html")
    Template postContent;

    @Inject
    LanguageContext languageContext;

    /**
     * @param slug Post slug
     * @return Post page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance post(@PathParam("slug") String slug,
                                 @Context HttpHeaders httpHeaders) {
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

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? postContent : post;

        return template
                .data("language", languageContext.getLang())
                .data("postSlug", slug)
                .data("postTitle", "文章: " + slug);
    }
}
