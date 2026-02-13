package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Link;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

/**
 * Link page for PJAX testing.
 */
@Path("/link")
public class LinkController {

        @Inject
        @Location("blog/link.html")
        Template link;

        @Inject
        @Location("blog/link.content.html")
        Template linkContent;

        @Inject
        LanguageContext languageContext;

        @GET
        @Produces(MediaType.TEXT_HTML)
        public TemplateInstance index(@Context HttpHeaders httpHeaders) {
                return render(httpHeaders);
        }

        private TemplateInstance render(HttpHeaders httpHeaders) {
                Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? linkContent : link;
                return template
                                .data("pageTitle", "Links")
                                .data("language", languageContext.getLang())
                                .data("links", Link.list("status = ?1 order by sortOrder asc, createdAt desc",
                                                (short) 1));
        }
}
