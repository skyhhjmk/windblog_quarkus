package com.biliwind.blog.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stateless, read-only A2A public content discovery. No tasks or account operations. */
@Path("/a2a")
public class A2aResource {
    @Inject
    AgentCardResource agentCard;

    @POST
    @Path("/message:send")
    @Consumes({"application/json", "application/a2a+json"})
    @Produces("application/a2a+json")
    public Response send(JsonNode request, @HeaderParam("A2A-Version") String version) {
        if (!"1.0".equals(version)) {
            return error(400, "VERSION_NOT_SUPPORTED", "Send A2A-Version: 1.0.");
        }
        JsonNode message = request == null ? null : request.get("message");
        if (message == null || !message.isObject()
                || !"ROLE_USER".equals(message.path("role").asText())
                || !message.path("messageId").isTextual() || message.path("messageId").asText().isBlank()
                || !message.path("parts").isArray() || message.path("parts").isEmpty()) {
            return error(400, "INVALID_ARGUMENT", "A user messageId and non-empty text parts are required.");
        }
        if (message.hasNonNull("taskId")) {
            return error(404, "TASK_NOT_FOUND", "This stateless agent does not retain tasks.");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : message.path("parts")) {
            if (!part.path("text").isTextual() || part.has("data") || part.has("url") || part.has("raw")) {
                return error(400, "CONTENT_TYPE_NOT_SUPPORTED", "Only text parts are supported.");
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(part.path("text").asText());
            if (text.length() > 4096) {
                return error(400, "INVALID_ARGUMENT", "Search text must be at most 4096 characters.");
            }
        }
        String query = text.toString().trim();
        if (query.isEmpty()) {
            return error(400, "INVALID_ARGUMENT", "Search text must not be blank.");
        }
        JsonNode outputModes = request.path("configuration").path("acceptedOutputModes");
        if (!outputModes.isMissingNode()) {
            boolean acceptsText = false;
            if (!outputModes.isArray()) {
                return error(400, "INVALID_ARGUMENT", "acceptedOutputModes must be an array.");
            }
            for (JsonNode mode : outputModes) {
                acceptsText |= "text/plain".equals(mode.asText());
            }
            if (!outputModes.isEmpty() && !acceptsText) {
                return error(400, "CONTENT_TYPE_NOT_SUPPORTED", "Only text/plain output is supported.");
            }
        }
        String baseUrl = agentCard.normalizedPublicUrl();
        String answer = "Search public articles: " + baseUrl + "/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "\nPublic API reference: " + baseUrl + "/api/public/openapi"
                + "\nPublic API documentation: " + baseUrl + "/api/public/docs";
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("messageId", UUID.randomUUID().toString());
        reply.put("role", "ROLE_AGENT");
        reply.put("parts", List.of(Map.of("text", answer)));
        if (message.path("contextId").isTextual()) {
            reply.put("contextId", message.path("contextId").asText());
        }
        return Response.ok(Map.of("message", reply)).header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/docs")
    @Produces("text/markdown; charset=utf-8")
    public String docs() {
        return """
                # WindBlog Public Content Agent

                Discover this read-only, stateless agent at `/.well-known/agent-card.json`.
                Send `POST /a2a/message:send` with `Content-Type: application/a2a+json`
                (application/json is also accepted) and `A2A-Version: 1.0`:

                ```json
                {"message":{"messageId":"example-1","role":"ROLE_USER","parts":[{"text":"Quarkus"}]}}
                ```

                The response contains an agent message with an encoded public article search URL,
                the public OpenAPI URL and its documentation URL. It does not generate article
                summaries or fetch private content. No credentials are required.
                Only text/plain input and output are supported; search text is limited to 4096 characters.
                Task persistence, streaming, push notifications and extended cards are not supported.
                A supplied contextId is echoed but no conversation history is retained.
                """;
    }

    private Response error(int status, String reason, String message) {
        return Response.status(status).entity(Map.of("error", Map.of(
                "code", status, "status", status == 404 ? "NOT_FOUND" : "INVALID_ARGUMENT",
                "message", message,
                "details", List.of(Map.of("@type", "type.googleapis.com/google.rpc.ErrorInfo",
                        "reason", reason, "domain", "a2a-protocol.org")))))
                .header("Cache-Control", "no-store").build();
    }
}
