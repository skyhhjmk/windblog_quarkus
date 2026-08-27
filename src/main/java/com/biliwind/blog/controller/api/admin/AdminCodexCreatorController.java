package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.ai.CodexCreatorHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.Map;
import java.util.concurrent.CompletionStage;

@Path("/api/admin/codex-creator")
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "adminBearerAuth")
public class AdminCodexCreatorController {
    @Inject
    CodexCreatorHttpClient client;

    @GET
    @Path("/status")
    @Operation(summary = "查询 Codex Creator 内部连接状态")
    public CompletionStage<Response> status() {
        return client.status()
                .thenApply(status -> Response.ok(Map.of("connected", true, "status", status)).build())
                .exceptionally(error -> Response.ok(Map.of("connected", false,
                        "error", rootMessage(error))).build());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
