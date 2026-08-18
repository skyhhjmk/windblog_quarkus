package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserNotification;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 用户中心站内通知。 */
@Path("/user/api/notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserNotification")
public class UserNotificationController {

    @Inject
    UserTokenVerifier tokenVerifier;

    @GET
    @Transactional
    @Operation(summary = "查询站内通知")
    public Response list(@Context HttpHeaders headers,
                         @QueryParam("page") @DefaultValue("1") int page,
                         @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        User user = requireUser(headers);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        List<UserNotification> all = UserNotification.list("user = ?1 order by createdAt desc", user);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<NotificationItem> items = new ArrayList<>();
        for (UserNotification notification : all.subList(from, to)) {
            items.add(toItem(notification));
        }
        long unread = UserNotification.count("user = ?1 and readAt is null", user);
        return Response.ok(Map.of("success", true, "data", items, "total", all.size(),
                "unread", unread, "page", safePage, "pageSize", safeSize)).build();
    }

    @POST
    @Path("/{id}/read")
    @Transactional
    @Operation(summary = "标记通知已读")
    public Response read(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        UserNotification notification = UserNotification.find("id = ?1 and user = ?2", id, user).firstResult();
        if (notification == null) {
            throw new NotFoundException("通知不存在");
        }
        if (notification.readAt == null) {
            notification.readAt = OffsetDateTime.now();
        }
        return Response.ok(Map.of("success", true)).build();
    }

    @POST
    @Path("/read-all")
    @Transactional
    @Operation(summary = "全部标记已读")
    public Response readAll(@Context HttpHeaders headers) {
        User user = requireUser(headers);
        UserNotification.update("readAt = ?1 where user = ?2 and readAt is null", OffsetDateTime.now(), user);
        return Response.ok(Map.of("success", true)).build();
    }

    private NotificationItem toItem(UserNotification notification) {
        return new NotificationItem(notification.id, notification.type, notification.title, notification.body,
                notification.targetUrl, notification.readAt, notification.createdAt);
    }

    private User requireUser(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            throw new NotAuthorizedException("请先登录");
        }
        UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        User user = verified == null ? null
                : User.find("id = ?1 and status = 1 and deletedAt is null", verified.uid()).firstResult();
        if (user == null) {
            throw new NotAuthorizedException("登录已过期");
        }
        return user;
    }

    public record NotificationItem(Long id, String type, String title, String body, String targetUrl,
                                   OffsetDateTime readAt, OffsetDateTime createdAt) {
    }
}
