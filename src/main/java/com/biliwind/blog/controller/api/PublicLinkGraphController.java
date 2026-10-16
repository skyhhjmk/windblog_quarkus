package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.service.InternalLinkGraphService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

@Path("/api/posts/{slug}/link-graph")
@Produces(MediaType.APPLICATION_JSON)
@io.smallrye.common.annotation.Blocking
public class PublicLinkGraphController {
    @Inject InternalLinkGraphService graph;
    @Inject RegionContext region;

    @GET
    public Response get(@PathParam("slug") String slug, @QueryParam("language") @DefaultValue("zh-cn") String language) {
        if (!graph.enabled()) return Response.status(404).header("Cache-Control", "no-store")
                .entity(java.util.Map.of("enabled", false)).build();
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        if (normalized == null) throw new BadRequestException("不支持的语言");
        try {
            return Response.ok(graph.publicGraph(slug, normalized, region.getCurrentRegion().getCode()))
                    .header("Cache-Control", "no-store").build();
        } catch (NotFoundException exception) {
            return Response.status(404).header("Cache-Control", "no-store")
                    .entity(java.util.Map.of("message", "文章不可用")).build();
        }
    }
}
