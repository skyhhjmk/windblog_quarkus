package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;
import com.biliwind.blog.context.PageIdentityContext;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.biliwind.blog.service.PageIdentityService;
import com.biliwind.blog.service.PublicContentSanitizer;
import com.biliwind.blog.service.CanonicalUrlService;
import com.biliwind.blog.i18n.I18n;

@Path("/")
public class LegalController {

    @Inject
    SettingsReader settings;

    @Inject
    PublicContentSanitizer sanitizer;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @Inject
    PageIdentityService pageIdentityService;

    @Inject
    PageIdentityContext pageIdentityContext;

    @Inject
    I18n i18n;

    @Inject
    PageRenderer pageRenderer;

    @GET
    @Path("/terms")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance terms(@Context HttpHeaders headers) {
        pageIdentityContext.set(pageIdentityService.aliased(
                canonicalUrlService.buildPath("/terms"), "/terms", "zh-CN"));
        return pageRenderer.render(PageView.LEGAL_TERMS, headers, new PageRenderModel()
                .put("language", "zh-CN")
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(headers))
                .put("pageTitle", i18n.t("user.terms"))
                .put("pageDescription", i18n.t("legal.terms_description"))
                // One configured document serves every language; keep a single
                // canonical URL instead of advertising fake translations.
                .put("legalContent", sanitizer.sanitize(settings.text(SettingKey.LEGAL_TERMS, "html"))));
    }

    @GET
    @Path("/privacy")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance privacy(@Context HttpHeaders headers) {
        pageIdentityContext.set(pageIdentityService.aliased(
                canonicalUrlService.buildPath("/privacy"), "/privacy", "zh-CN"));
        return pageRenderer.render(PageView.LEGAL_PRIVACY, headers, new PageRenderModel()
                .put("language", "zh-CN")
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(headers))
                .put("pageTitle", i18n.t("user.privacy"))
                .put("pageDescription", i18n.t("legal.privacy_description"))
                .put("legalContent", sanitizer.sanitize(settings.text(SettingKey.LEGAL_PRIVACY, "html"))));
    }

}
