package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
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

import java.util.List;
import java.util.Map;

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
        return render(httpHeaders, "Links", false);
    }

    @GET
    @Path("/pjax-test")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance pjaxTest(@Context HttpHeaders httpHeaders) {
        return render(httpHeaders, "Links PJAX Test", true);
    }

    private TemplateInstance render(HttpHeaders httpHeaders, String pageTitle, boolean pjaxTestMode) {
        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? linkContent : link;
        return template
                .data("pageTitle", pageTitle)
                .data("pjaxTestMode", pjaxTestMode)
                .data("language", languageContext.getLang())
                .data("links", buildMockLinks());
    }

    private List<Map<String, Object>> buildMockLinks() {
        return List.of(
                Map.of(
                        "id", 1,
                        "name", "Quarkus",
                        "url", "https://quarkus.io",
                        "target", "_blank",
                        "icon", "",
                        "createdAt", "2026-02-12",
                        "protocol", "CAT5",
                        "score", 98,
                        "redirectType", "direct",
                        "description", "Supersonic Subatomic Java framework."
                ),
                Map.of(
                        "id", 2,
                        "name", "Qute Reference",
                        "url", "https://quarkus.io/guides/qute",
                        "target", "_blank",
                        "icon", "",
                        "createdAt", "2026-02-12",
                        "protocol", "CAT4",
                        "score", 92,
                        "redirectType", "info",
                        "description", "Template engine guide and syntax reference."
                ),
                Map.of(
                        "id", 3,
                        "name", "MDN Web Docs",
                        "url", "https://developer.mozilla.org",
                        "target", "_blank",
                        "icon", "",
                        "createdAt", "2026-02-12",
                        "protocol", "CAT3",
                        "score", 88,
                        "redirectType", "goto",
                        "description", "Frontend and browser API documentation."
                )
        );
    }
}
