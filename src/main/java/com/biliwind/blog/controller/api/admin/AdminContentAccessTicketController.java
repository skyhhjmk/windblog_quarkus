package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.ContentAccessTicketService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/admin/security/content-access-tickets")
@Produces(MediaType.APPLICATION_JSON)
public class AdminContentAccessTicketController {

    @Inject
    ContentAccessTicketService ticketService;

    @POST
    @Path("/rotate-key")
    @Transactional
    public Response rotateKey() {
        int version = ticketService.rotateKeyVersion();
        return Response.ok(Map.of(
                "success", true,
                "keyVersion", version,
                "message", "旧的文章和媒体访问票据已全局撤销")).build();
    }

    @POST
    @Path("/revoke")
    @Transactional
    public Response revoke(
            @QueryParam("mediaId") Long mediaId,
            @QueryParam("postId") Long postId,
            @QueryParam("userId") Long userId,
            @QueryParam("deviceId") String deviceId) {
        if (mediaId == null && postId == null && userId == null
                && (deviceId == null || deviceId.isBlank())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "至少提供一个撤销范围")).build();
        }
        long revoked = ticketService.revokeMediaDownloadTickets(mediaId, postId, userId, deviceId);
        return Response.ok(Map.of("success", true, "revoked", revoked)).build();
    }
}
