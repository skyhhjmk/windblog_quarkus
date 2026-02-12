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
 * Index controller
 */
@Path("/")
public class IndexController {

    @Inject
    @Location("blog/index.html")
    Template index;

    @Inject
    @Location("blog/index.content.html")
    Template indexContent;

    @Inject
    LanguageContext languageContext;

    /**
     * @param httpHeaders HttpHeaders
     * @return Index page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders httpHeaders) {
        return subPage(1, httpHeaders);
    }

    /**
     * @param subPage page number
     * @param httpHeaders HttpHeaders
     * @return subPage page
     */
    @Path("/page/{subPage}")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subPage(@PathParam("subPage") Integer subPage,
                                    @Context HttpHeaders httpHeaders) {
        if (subPage == null || subPage < 1) {
            subPage = 1;
        }

        Template template = PjaxHelper.isPjaxRequest(httpHeaders) ? indexContent : index;

        return template
                .data("language", languageContext.getLang())
                .data("subPage", subPage);
    }
}
