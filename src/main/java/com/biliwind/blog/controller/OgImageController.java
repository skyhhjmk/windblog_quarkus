package com.biliwind.blog.controller;

import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.og.OgImageService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Serves dynamic OpenGraph SVG and PNG preview cards for published posts and tools.
 */
@Path("/og")
@Blocking
public class OgImageController {

    @Inject
    OgImageService ogImageService;

    @Inject
    LanguageContext languageContext;

    @Inject
    I18n i18n;

    @GET
    @Path("/post/{slug}.svg")
    @Produces("image/svg+xml; charset=utf-8")
    public Response postOgSvg(@PathParam("slug") String slug) {
        String svg = ogImageService.getPostOgSvg(slug);
        return Response.ok(svg, "image/svg+xml; charset=utf-8")
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    @GET
    @Path("/post/{slug}.png")
    @Produces("image/png")
    public Response postOgPng(@PathParam("slug") String slug) {
        byte[] png = ogImageService.getPostOgPng(slug);
        return Response.ok(png, "image/png")
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }

    @GET
    @Path("/tool/{toolId}.png")
    @Produces("image/png")
    public Response toolOgPng(@PathParam("toolId") String toolId) {
        ToolsController.ToolItem tool = ToolsController.findTool(toolId);
        if (tool == null) {
            throw new NotFoundException("Unknown tool: " + toolId);
        }
        String lang = languageContext.getLang();
        String title = i18n.t(tool.nameKey());
        String desc = i18n.t(tool.descriptionKey());
        String tag = i18n.t(tool.tagKey());
        byte[] png = ogImageService.getToolOgPng(tool.id(), title, desc, tag, lang);
        return Response.ok(png, "image/png")
                .header("Cache-Control", "public, max-age=86400")
                .build();
    }
}
