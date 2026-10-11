package com.biliwind.blog.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;

@Path("/.well-known/agent-card.json")
public class AgentCardResource {

    private static final String AGENT_VERSION = "1.0.0";

    @ConfigProperty(name = "windblog.site.public-url", defaultValue = "http://localhost:8080")
    String publicUrl;

    @GET
    @Produces("application/json")
    public Response getAgentCard() {
        String baseUrl = normalizedPublicUrl();
        Map<String, Object> card = Map.of(
                "name", "WindBlog Public Content Agent",
                "description", "Helps agent clients discover WindBlog's public articles and unauthenticated HTTP APIs.",
                "version", AGENT_VERSION,
                "supportedInterfaces", List.of(Map.of(
                        "url", baseUrl + "/a2a",
                        "protocolBinding", "HTTP+JSON",
                        "protocolVersion", "1.0"
                )),
                "provider", Map.of(
                        "organization", "WindBlog",
                        "url", baseUrl
                ),
                "documentationUrl", baseUrl + "/a2a/docs",
                "capabilities", Map.of(
                        "streaming", false,
                        "pushNotifications", false,
                        "extendedAgentCard", false
                ),
                "defaultInputModes", List.of("text/plain"),
                "defaultOutputModes", List.of("text/plain"),
                "skills", List.of(
                        Map.of(
                                "id", "public-article-discovery",
                                "name", "Public article discovery",
                                "description", "Provides a public article search URL for a supplied search phrase.",
                                "tags", List.of("articles", "search", "titles"),
                                "examples", List.of("Find public articles matching a search phrase.")
                        ),
                        Map.of(
                                "id", "public-api-reference",
                                "name", "Public API reference",
                                "description", "Provides links to the OpenAPI reference and documentation for WindBlog's unauthenticated public APIs.",
                                "tags", List.of("api", "openapi", "documentation"),
                                "examples", List.of("List the public WindBlog API operations.")
                        )
                )
        );

        return Response.ok(card)
                .header("Cache-Control", "public, max-age=300")
                .build();
    }

    String normalizedPublicUrl() {
        String baseUrl = publicUrl == null ? "" : publicUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
