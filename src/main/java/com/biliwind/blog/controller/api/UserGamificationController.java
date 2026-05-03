package com.biliwind.blog.controller.api;

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
import java.util.stream.Collectors;

@Path("/api/user")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserGamificationController {

    @Inject
    StoreService storeService;

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
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("success", false, "message", e.getMessage())).build();
        }
    }

    @POST
    @Path("/buy-store-item/{itemId}")
    @Transactional
    public Response buyStoreItem(@PathParam("itemId") Long itemId, @Context HttpHeaders headers) {
        Long userId = resolveUserIdFromCookie(headers);
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity(Map.of("success", false, "message", "请先登录")).build();
        }

        try {
            storeService.buyStoreItem(userId, itemId);
            return Response.ok(Map.of("success", true, "message", "购买成功")).build();
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("success", false, "message", e.getMessage())).build();
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

        List<UserBackpackItem> backpack = storeService.getUserBackpack(userId);

        // 组装返回数据，包含背包物品的详情
        List<Map<String, Object>> backpackDetails = backpack.stream().map(item -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", item.id);
            map.put("storeItemId", item.storeItemId);
            map.put("acquiredAt", item.acquiredAt);

            com.biliwind.blog.model.StoreItem sItem = com.biliwind.blog.model.StoreItem.findById(item.storeItemId);
            if (sItem != null) {
                map.put("name", sItem.name);
                map.put("rarity", sItem.rarity);
                map.put("type", sItem.type);
                map.put("description", sItem.description);
                map.put("extraInfo", sItem.extraInfo);
            }
            return map;
        }).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("level", user.level);
        data.put("exp", user.exp);
        data.put("backpackCapacity", user.backpackCapacity);
        data.put("backpack", backpackDetails);

        return Response.ok(Map.of("success", true, "data", data)).build();
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
