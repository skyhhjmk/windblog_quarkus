package com.biliwind.blog.controller;

import com.biliwind.blog.service.IndexNowService;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Exposes the IndexNow key verification file at {@code /{key}.txt}.
 */
@Path("/")
@Blocking
public class IndexNowController {

    @Inject
    IndexNowService indexNowService;

    @GET
    @Path("/{key: [a-zA-Z0-9\\-]{8,128}}.txt")
    @Produces(MediaType.TEXT_PLAIN)
    public Response verifyKey(@PathParam("key") String key) {
        if (!indexNowService.isEnabled()) {
            throw new NotFoundException();
        }
        String configuredKey = indexNowService.getConfiguredKey();
        if (key == null || configuredKey.isEmpty()
                || !MessageDigest.isEqual(
                        key.getBytes(StandardCharsets.UTF_8),
                        configuredKey.getBytes(StandardCharsets.UTF_8))) {
            throw new NotFoundException();
        }
        return Response.ok(configuredKey, MediaType.TEXT_PLAIN)
                .header("Cache-Control", "public, max-age=3600")
                .build();
    }
}
