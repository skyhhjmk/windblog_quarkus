package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserBackpackItem;
import com.biliwind.blog.service.PostAccessService;
import com.biliwind.blog.service.StoreService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/user")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserGamificationController {

    @Inject
    StoreService storeService;

    @Inject
    com.biliwind.blog.service.inventory.BackpackService backpackService;

    @Inject
    PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @POST
    @Path("/buy-post/{postId}")
    @Transactional
    public Response buyPost(@PathParam("postId") Long postId, @Context HttpHeaders headers) {
        Long userId = resolveUserIdFromCookie(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false, "message", "请先登录")).build();
        }

        try {
            postAccessService.buyPost(userId, postId, null, null);
            return Response.ok(Map.of("success", true, "message", "购买成功")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @POST
    @Path("/buy-store-item/{itemId}")
    @Transactional
    public Response buyStoreItem(@PathParam("itemId") Long itemId,
                                 @HeaderParam("Idempotency-Key") String idempotencyKey,
                                 @Context HttpHeaders headers) {
        Long userId = resolveUserIdFromCookie(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false, "message", "请先登录")).build();
        }

        try {
            storeService.buyStoreItem(userId, itemId, idempotencyKey);
            return Response.ok(Map.of("success", true, "message", "购买成功")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of(
                    "success", false,
                    "message", SensitiveMessageSanitizer.sanitize(e.getMessage()))).build();
        }
    }

    @GET
    @Path("/gamification-info")
    public Response getGamificationInfo(@Context HttpHeaders headers) {
        Long userId = resolveUserIdFromCookie(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false, "message", "请先登录")).build();
        }

        User user = User.findById(userId);
        if (user == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false, "message", "用户不存在")).build();
        }

        com.biliwind.blog.service.inventory.InventorySnapshot inventory = backpackService.getSnapshot(userId);

        Map<String, Object> data = new HashMap<>();
        data.put("level", user.level);
        data.put("exp", user.exp);
        data.put("backpackCapacity", user.backpackCapacity);
        data.put("backpack", inventory.items());
        data.put("inventoryRevision", inventory.revision());
        data.put("inventoryContainers", inventory.containers());

        return Response.ok(Map.of("success", true, "data", data)).build();
    }

    @GET
    @Path("/purchased-posts")
    public Response getPurchasedPosts(@Context HttpHeaders headers) {
        Long userId = resolveUserIdFromCookie(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("success", false, "message", "请先登录"))
                    .build();
        }
        List<com.biliwind.blog.model.UserPurchaseRecord> records = com.biliwind.blog.model.UserPurchaseRecord
                .find("userId = ?1 and targetType = 'POST' order by createdAt desc", userId)
                .list();

        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (var record : records) {
            com.biliwind.blog.model.Post post = com.biliwind.blog.model.Post.findById(record.targetId);
            if (post == null || post.deletedAt != null) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("recordId", record.id);
            item.put("postId", post.id);
            item.put("slug", post.slug);
            item.put("title", post.title != null ? post.title.getOrDefault("zh-CN",
                    post.title.values().stream().findFirst().orElse(post.slug)) : post.slug);
            item.put("blockId", record.targetBlockId);
            item.put("pointsPaid", record.pointsPaid);
            item.put("purchasedAt", record.createdAt);
            items.add(item);
        }
        return Response.ok(Map.of("success", true, "data", items)).build();
    }

    private Long resolveUserIdFromCookie(HttpHeaders headers) {
        jakarta.ws.rs.core.Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }
        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        if (verified == null) {
            return null;
        }
        return verified.uid();
    }
}
