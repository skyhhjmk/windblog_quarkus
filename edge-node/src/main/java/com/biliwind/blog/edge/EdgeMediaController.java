package com.biliwind.blog.edge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/")
public class EdgeMediaController {
    private static final Logger log = LoggerFactory.getLogger(EdgeMediaController.class);

    @Inject
    EdgeRoutingService routingService;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String status() {
        return "WindBlog Edge Node is running and ready to serve media.";
    }

    @GET
    @Path("/uploads/{path: .*}")
    @Produces(MediaType.WILDCARD)
    public Response getFile(@PathParam("path") String path) {
        log.info("Edge node receiving request for path: {}", path);

        String bestUrl = routingService.getBestAccessUrl(path, "original");

        if (bestUrl != null) {
            return Response.status(Response.Status.FOUND)
                    .header("Location", bestUrl)
                    .build();
        }

        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
