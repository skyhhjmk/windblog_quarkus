package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;

@Path("/")
public class LegalController {

    @Inject
    @Location("legal/terms.html")
    Template termsTemplate;

    @Inject
    @Location("legal/terms.content.html")
    Template termsContentTemplate;

    @Inject
    @Location("legal/privacy.html")
    Template privacyTemplate;

    @Inject
    @Location("legal/privacy.content.html")
    Template privacyContentTemplate;

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? termsContentTemplate : termsTemplate;
        return template.data("language", "zh-CN");
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? privacyContentTemplate : privacyTemplate;
        return template.data("language", "zh-CN");
    }
}
