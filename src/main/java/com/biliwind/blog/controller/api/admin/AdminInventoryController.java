package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.User;
import com.biliwind.blog.service.inventory.BackpackService;
import com.biliwind.blog.service.inventory.InventorySnapshot;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Administrator endpoints for inspecting and granting a user's inventory. */
@Path("/api/admin/users/{userId}/inventory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminInventory")
public class AdminInventoryController {

    @Inject
    BackpackService backpackService;

    @GET
    @Operation(summary = "查询用户仓库")
    public InventorySnapshot getInventory(@PathParam("userId") Long userId) {
        requireUser(userId);
        return backpackService.getSnapshot(userId);
    }

    @POST
    @Path("/grant")
    @Operation(summary = "手动发放仓库物品")
    public Response grant(
            @PathParam("userId") Long userId,
            GrantRequest request,
            @HeaderParam("Idempotency-Key") String idempotencyKey) {
        requireUser(userId);
        if (request == null || request.storeItemId() == null) {
            throw new BadRequestException("请选择物品");
        }
        if (request.quantity() == null || request.quantity() < 1) {
            throw new BadRequestException("物品数量必须大于 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key 必须存在");
        }

        backpackService.grant(
                userId,
                request.storeItemId(),
                request.quantity(),
                request.reason() == null || request.reason().isBlank() ? "ADMIN" : request.reason().trim(),
                idempotencyKey);
        return Response.ok(backpackService.getSnapshot(userId)).build();
    }

    private void requireUser(Long userId) {
        User user = User.findById(userId);
        if (user == null || user.deletedAt != null) {
            throw new NotFoundException("用户不存在");
        }
    }

    public record GrantRequest(Long storeItemId, Integer quantity, String reason) {
    }
}
