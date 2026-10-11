package com.biliwind.blog.controller;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/auth.md")
public class AuthDiscoveryResource {

    private static final String AUTH_MD = """
            # WindBlog auth.md

            ## Audience and supported authentication

            This document is for software agents acting on behalf of a person who has a WindBlog account. WindBlog does not currently provide OAuth authorization-server metadata, OAuth protected-resource metadata, or a separate agent identity or agent credential flow. Agents cannot register an agent identity with this service.

            WindBlog user APIs use the `user_token` HTTP cookie. They do not document or issue OAuth access tokens, API keys, refresh tokens, or bearer credentials for agents.

            ## Account registration and provisioning

            A person can create a regular WindBlog user account with:

            - Endpoint: `POST /user/api/register`
            - Content type: `application/x-www-form-urlencoded`
            - Required fields: `username` (3–20 letters, digits, or underscores), `email`, `password` (6–32 characters), `confirmPassword`, and `agreement=on`
            - Optional fields: `subscribeArticleUpdates=on` and `subscribePromotions=on`

            Registration creates a user account, sends a verification email, and returns a `user_token` cookie. Registration may be disabled by the site operator or rate limited. This endpoint has account-creation and email side effects; discovery clients must not submit registration requests during passive scans. A user should complete registration through the WindBlog site and authorize any agent to use that account.

            An existing account can establish a new session with:

            - Endpoint: `POST /user/api/login`
            - Content type: `application/x-www-form-urlencoded`
            - Required fields: `account` (username or email) and `password`
            - Optional field: `remember=on`

            Successful login returns a `user_token` cookie. Login is subject to rate limits.

            ## Credential use

            For user API requests, retain the `user_token` value from the `Set-Cookie` response and send it as `Cookie: user_token=<value>`. Treat it as a password: keep it secret, store it securely, and do not place it in URLs, logs, or prompts. The cookie is HTTP-only when issued by the service. WindBlog does not accept this account cookie as an OAuth bearer token, and it does not grant an agent-specific identity or scopes.

            ## OAuth and agent metadata

            No OAuth authorization server is advertised, so there is no OAuth Protected Resource Metadata document, Authorization Server Metadata document, or automated agent registration method to publish. No ID-JAG, verified-email, or anonymous agent credential flow is currently advertised.
            """;

    @GET
    @Produces("text/markdown; charset=utf-8")
    public Response getAuthDocument() {
        return Response.ok(AUTH_MD)
                .type("text/markdown; charset=utf-8")
                .header("Cache-Control", "public, max-age=300")
                .build();
    }
}
