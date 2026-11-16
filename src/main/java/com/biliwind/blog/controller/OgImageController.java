package com.biliwind.blog.controller;

import com.biliwind.blog.service.og.OgImageService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Serves dynamic OpenGraph SVG and PNG preview cards for published posts.
 */
@Path("/og")
@Blocking
public class OgImageController {

    @Inject
    OgImageService ogImageService;

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
}
