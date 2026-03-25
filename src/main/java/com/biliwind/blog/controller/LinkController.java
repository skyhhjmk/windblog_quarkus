package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkType;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

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
        public TemplateInstance index(
                        @Context HttpHeaders httpHeaders,
                        @QueryParam("type") String type) {
                return render(httpHeaders, type);
        }

        private TemplateInstance render(HttpHeaders httpHeaders, String type) {
                Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? linkContent : link;
                
                List<Link> links;
                if (type == null || type.equals("All")) {
                        links = Link.list("status = ?1 order by sortOrder asc, createdAt desc", (short) 1);
                } else {
                        LinkType linkType = parseLinkType(type);
                        if (linkType != null) {
                                links = Link.list("status = ?1 and type = ?2 order by sortOrder asc, createdAt desc",
                                                (short) 1, linkType);
                        } else {
                                links = Link.list("status = ?1 order by sortOrder asc, createdAt desc", (short) 1);
                        }
                }
                
                return template
                                .data("pageTitle", "Links")
                                .data("language", languageContext.getLang())
                                .data("links", links);
        }
        
        private LinkType parseLinkType(String type) {
                if ("friend_links".equals(type)) {
                        return LinkType.FRIENDLY_LINK;
                } else if ("other_links".equals(type)) {
                        return LinkType.OTHER;
                }
                return null;
        }
}
