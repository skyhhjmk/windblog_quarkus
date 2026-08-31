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
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;

import java.time.OffsetDateTime;
import java.util.Optional;

@Path("/")
public class AmpController {

    @Inject
    @Location("blog/amp.html")
    Template ampTemplate;

    @Inject
    AmpPageService ampPageService;

    @GET
    @Path("/amp/post/{slug}")
    @Produces(MediaType.TEXT_HTML)
    public Response renderDefault(@PathParam("slug") String slug, Request request) {
        return render(slug, null, request);
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
        EntityTag entityTag = new EntityTag(buildEtag(post));
        Response.ResponseBuilder precondition = request.evaluatePreconditions(entityTag);
        if (precondition != null) {
            CacheControl cacheControl = new CacheControl();
            cacheControl.setMaxAge(300);
            return precondition.cacheControl(cacheControl)
                    .header("Vary", "Accept-Language")
                    .build();
        }

        TemplateInstance instance = ampTemplate
                .data("language", post.language())
                .data("title", post.title())
                .data("description", post.summary())
                .data("postBody", post.html())
                .data("canonicalUrl", post.canonicalUrl())
                .data("ampUrl", post.ampUrl())
                .data("publishedAt", post.lastUpdated())
                .data("imageCount", post.imageCount())
                .data("repostPolicyName", post.repostPolicyName())
                .data("repostPolicyRequiresApplication", post.repostPolicyRequiresApplication())
                .data("repostPolicyConditions", post.repostPolicyConditions())
                .data("repostPolicyLicenseUrl", post.repostPolicyLicenseUrl())
                .data("repostOriginalUrl", post.repostOriginalUrl());
        CacheControl cacheControl = new CacheControl();
        cacheControl.setMaxAge(300);
        return Response.ok(instance)
                .tag(entityTag)
                .cacheControl(cacheControl)
                .header("Vary", "Accept-Language")
                .build();
    }

    private String buildEtag(AmpPageService.AmpPost post) {
        OffsetDateTime lastUpdated = post.lastUpdated();
        long epoch = lastUpdated == null ? 0L : lastUpdated.toEpochSecond();
        return post.slug() + "_" + epoch + "_" + post.language();
    }
}
