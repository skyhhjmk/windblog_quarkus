package com.biliwind.blog.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;

@Path("/api/public")
public class PublicApiPublicationResource {

    @GET
    @Path("/openapi")
    @Produces("application/vnd.oai.openapi+json;version=3.1")
    public Response openApi() {
        return Response.ok(readResource("/public-api-openapi.json"))
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    @GET
    @Path("/docs")
    @Produces("text/html; charset=UTF-8")
    public Response docs() {
        return Response.ok(readResource("/public-api-docs.html"))
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    private byte[] readResource(String resourcePath) {
        try (InputStream input = getClass().getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new InternalServerErrorException("Public API publication resource is missing");
            }
            return input.readAllBytes();
        } catch (IOException e) {
            throw new InternalServerErrorException("Unable to read public API publication resource", e);
        }
    }
}
