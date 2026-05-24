package com.biliwind.blog.controller;

import com.biliwind.blog.service.repost.GoRedirectService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * go 短链跳转端点。
 */
@Path("/r")
public class GoRedirectController {

    @Inject
    GoRedirectService goRedirectService;

    @GET
    @Path("/{token}")
    @Produces(MediaType.TEXT_HTML)
    public Response redirect(@PathParam("token") String token, @Context HttpHeaders httpHeaders) {
        return goRedirectService.redirect(token, httpHeaders);
    }
}
