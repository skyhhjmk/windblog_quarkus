package com.biliwind.blog.edge;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/uploads")
public class EdgeMediaController {
    private static final Logger log = LoggerFactory.getLogger(EdgeMediaController.class);

    @Inject
    EdgeRoutingService routingService;

    @GET
    @Path("/{fileName}")
    @Produces(MediaType.WILDCARD)
    public Response getFile(@PathParam("fileName") String fileName) {
        log.info("Edge node receiving request for: {}", fileName);

        String bestUrl = routingService.getBestAccessUrl(fileName, "original");

        if (bestUrl != null) {
            return Response.status(Response.Status.FOUND)
                    .header("Location", bestUrl)
                    .build();
        }

        // Fallback or 404
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
