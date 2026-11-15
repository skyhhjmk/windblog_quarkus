package com.biliwind.blog.controller;

import com.biliwind.blog.service.PublicToolboxService;
import com.biliwind.blog.service.PublicUrlService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unauthenticated stateless public HTTP API for developer toolbox utilities.
 */
@Path("/api/public/tools")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class PublicToolsApiController {

    @Inject
    PublicToolboxService toolboxService;

    @Inject
    PublicUrlService publicUrlService;

    @GET
    public Response listTools() {
        String baseUrl = publicUrlService.getBaseUrl();
        List<Map<String, Object>> webTools = ToolsController.allTools().stream()
                .map(t -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", t.id());
                    item.put("name", t.name());
                    item.put("tag", t.tag());
                    item.put("description", t.description());
                    item.put("url", baseUrl + t.url());
                    return item;
                })
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tools", webTools);
        body.put("mcpEndpoint", baseUrl + "/api/public/mcp");
        body.put("endpoints", Map.of(
                "subnet", baseUrl + "/api/public/tools/subnet?cidr=192.168.1.10/24",
                "timestamp", baseUrl + "/api/public/tools/timestamp?value=now",
                "cron", baseUrl + "/api/public/tools/cron?expr=0%20*/5%20*%20*%20*&count=5",
                "hash", baseUrl + "/api/public/tools/hash",
                "tokenEstimate", baseUrl + "/api/public/tools/token-estimate"
        ));
        return Response.ok(body)
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    @GET
    @Path("/subnet")
    public Response subnet(@QueryParam("cidr") String cidr) {
        return safeRun(() -> toolboxService.calculateSubnet(cidr));
    }

    @GET
    @Path("/timestamp")
    public Response timestamp(@QueryParam("value") @DefaultValue("now") String value) {
        return safeRun(() -> toolboxService.convertTimestamp(value));
    }

    @GET
    @Path("/cron")
    public Response cron(@QueryParam("expr") String expr,
                         @QueryParam("count") @DefaultValue("5") int count) {
        return safeRun(() -> toolboxService.nextCronRuns(expr, count));
    }

    @POST
    @Path("/hash")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response hash(JsonNode body) {
        if (body == null || !body.isObject()) {
            return badRequest("JSON object body with 'text' is required");
        }
        String text = body.path("text").isTextual() ? body.path("text").asText() : null;
        String algorithm = body.path("algorithm").asText("SHA-256");
        return safeRun(() -> toolboxService.computeHash(text, algorithm));
    }

    @POST
    @Path("/token-estimate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tokenEstimate(JsonNode body) {
        if (body == null || !body.isObject()) {
            return badRequest("JSON object body with 'text' is required");
        }
        String text = body.path("text").isTextual() ? body.path("text").asText() : "";
        return safeRun(() -> toolboxService.estimateTokens(text));
    }

    private Response safeRun(java.util.function.Supplier<Map<String, Object>> supplier) {
        try {
            return Response.ok(supplier.get())
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", "INVALID_ARGUMENT", "message", message == null ? "Bad request" : message))
                .build();
    }
}
