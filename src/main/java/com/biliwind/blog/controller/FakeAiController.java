package com.biliwind.blog.controller;

import com.biliwind.blog.service.FakeAiService;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/fake-ai/{provider}/v1")
@Produces(MediaType.APPLICATION_JSON)
public class FakeAiController {
    @Inject
    FakeAiService service;

    @GET
    @Path("/models")
    public Response models(@PathParam("provider") String provider) {
        return service.models(provider);
    }

    @POST
    @Path("/{endpoint:chat/completions|messages}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces({MediaType.APPLICATION_JSON, MediaType.SERVER_SENT_EVENTS})
    @RunOnVirtualThread
    public Response generate(@PathParam("provider") String provider,
                             @PathParam("endpoint") String endpoint, JsonNode request) {
        return service.generate(provider, endpoint, request);
    }
}
