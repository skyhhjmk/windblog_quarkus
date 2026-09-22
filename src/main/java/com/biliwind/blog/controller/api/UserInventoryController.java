package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.service.inventory.InventorySnapshot;
import com.biliwind.blog.service.inventory.BackpackService;
import com.biliwind.blog.service.inventory.UseRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.UUID;

@Path("/api/user/inventory")
@Produces("application/json")
@Consumes("application/json")
public class UserInventoryController {
    @Inject BackpackService backpackService;
    @Inject UserTokenVerifier tokenVerifier;
    @Inject ObjectMapper objectMapper;

    @GET
    public Response snapshot(@Context HttpHeaders headers) {
        Long userId = userId(headers);
        return userId == null ? unauthorized() : Response.ok(backpackService.getSnapshot(userId)).build();
    }

    @POST
    @Path("/sync")
    public Response sync(@Context HttpHeaders headers) {
        Long userId = userId(headers);
        return userId == null ? unauthorized() : Response.ok(backpackService.getSnapshot(userId)).build();
    }

    @PUT
    public Response apply(JsonNode body,
                          @HeaderParam("Idempotency-Key") String headerOperationId,
                          @Context HttpHeaders headers) {
        Long userId = userId(headers);
        if (userId == null) return unauthorized();
        try {
            JsonNode payload = body == null ? objectMapper.createObjectNode() : body;
            long baseRevision = payload.path("baseRevision").asLong(-1);
            if (baseRevision < 0) return badRequest("baseRevision 必须存在");
            String operationId = payload.path("operationId").asText(headerOperationId);
            if (operationId == null || operationId.isBlank()) return badRequest("operationId 必须存在");
            JsonNode snapshotNode = payload.has("snapshot") ? payload.get("snapshot") : payload;
            InventorySnapshot snapshot = objectMapper.treeToValue(snapshotNode, InventorySnapshot.class);
            return Response.ok(backpackService.applySnapshot(userId, baseRevision, snapshot, operationId)).build();
        } catch (BackpackService.ConflictException e) {
            return Response.status(Response.Status.CONFLICT).entity(Map.of(
                    "success", false, "message", "仓库版本已变化", "revision", e.currentRevision(), "snapshot", e.current())).build();
        } catch (BadRequestException | IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("success", false, "message", "仓库同步失败")).build();
        }
    }

    @POST
    @Path("/items/{instanceUuid}/use")
    public Response use(@PathParam("instanceUuid") UUID instanceUuid,
                        @HeaderParam("Idempotency-Key") String idempotencyKey,
                        JsonNode body,
                        @Context HttpHeaders headers) {
        Long userId = userId(headers);
        if (userId == null) return unauthorized();
        if (idempotencyKey == null || idempotencyKey.isBlank()) return badRequest("Idempotency-Key 必须存在");
        var result = backpackService.use(userId, instanceUuid,
                new UseRequest(body == null || !body.isObject() ? Map.of() : objectMapper.convertValue(body, Map.class)),
                idempotencyKey);
        return result.success() ? Response.ok(Map.of("success", true, "message", result.message())).build()
                : Response.status(Response.Status.BAD_REQUEST).entity(Map.of("success", false, "message", result.message())).build();
    }

    private Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("success", false, "message", message == null ? "参数错误" : message)).build();
    }

    private Long userId(HttpHeaders headers) {
        var cookie = headers.getCookies().get("user_token");
        if (cookie == null) return null;
        UserTokenVerifier.VerifiedToken token = tokenVerifier.verify(cookie.getValue());
        return token == null ? null : token.uid();
    }
    private Response unauthorized() { return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false)).build(); }
}
