package com.biliwind.blog.controller;

import com.biliwind.blog.service.AmpPageService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;

import java.util.Optional;

@Path("/")
@io.smallrye.common.annotation.Blocking
public class AmpController {

    @Inject
    @Location("blog/amp.html")
    Template ampTemplate;

    @Inject
    AmpPageService ampPageService;

    @Inject
    com.biliwind.blog.service.PageIdentityService pageIdentityService;

    @Inject
    com.biliwind.blog.context.PageIdentityContext pageIdentityContext;

    @Inject
    com.biliwind.blog.context.LanguageContext languageContext;

    @GET
    @Path("/amp/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public Response renderDefault(@PathParam("slug") String slug, Request request) {
        return render(slug, languageContext.getLang(), request);
    }

    @GET
    @Path("/{langCode}/amp/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public Response renderLanguage(@PathParam("langCode") String languageCode,
                                   @PathParam("slug") String slug,
                                   Request request) {
        return render(slug, languageCode, request);
    }

    private Response render(String slug, String languageCode, Request request) {
        Optional<AmpPageService.AmpPost> result = ampPageService.find(slug, languageCode);
        if (!ampPageService.isEnabled() || result.isEmpty() || !result.get().available()) {
            throw new NotFoundException("AMP page not found");
        }
        AmpPageService.AmpPost post = result.get();
        pageIdentityContext.set(post.identity());

        com.biliwind.blog.service.PageRenderModel model =
                new com.biliwind.blog.service.PageRenderModel()
                        .put("jsonLd", post.jsonLd())
                        .put("language", post.language())
                        .put("title", post.title())
                        .put("description", post.summary())
                        .put("postBody", post.html())
                        .put("canonicalUrl", post.canonicalUrl())
                        .put("ampUrl", post.ampUrl())
                        .put("publishedAt", post.lastUpdated())
                        .put("imageCount", post.imageCount())
                        .put("repostPolicyName", post.repostPolicyName())
                        .put("repostPolicySummary", post.repostPolicySummary())
                        .put("repostPolicyRequiresApplication", post.repostPolicyRequiresApplication())
                        .put("repostPolicyConditions", post.repostPolicyConditions())
                        .put("repostPolicyLicenseUrl", post.repostPolicyLicenseUrl())
                        .put("repostOriginalUrl", post.repostOriginalUrl())
                        .put("pageIdentity", post.identity())
                        .put("siteConfig", pageIdentityService.environmentFingerprint());

        EntityTag entityTag = model.validator();
        Response.ResponseBuilder precondition = request.evaluatePreconditions(entityTag);
        if (precondition != null) {
            return com.biliwind.blog.service.PageCachePolicy
                    .publicDocument(precondition, entityTag, 300, 300).build();
        }

        TemplateInstance instance = model.render(ampTemplate);
        return com.biliwind.blog.service.PageCachePolicy
                .publicDocument(Response.ok(instance), entityTag, 300, 300)
                .build();
    }
}
