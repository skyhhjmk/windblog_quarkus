package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.service.repost.RepostLicenseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户转载授权 API。
 */
@Path("/api/user/repost/licenses")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserRepostLicense")
public class UserRepostLicenseController {

    @Inject
    RepostLicenseService repostLicenseService;

    @Inject
    UserTokenVerifier tokenVerifier;

    @POST
    @Operation(summary = "申请转载授权")
    public Response createLicense(LicenseCreateRequest request, @Context HttpHeaders httpHeaders) {
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized();
        }

        RepostLicenseService.LicenseCreationResult result =
                repostLicenseService.createLicense(request.postId, userId, request.targetUrl);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", buildLicenseResponse(result.license, result.copyContent));
        return Response.ok(body).build();
    }

    @GET
    @Path("/{id}/copy")
    @Operation(summary = "获取转载复制内容")
    public Response getCopyContent(@PathParam("id") Long id, @Context HttpHeaders httpHeaders) {
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized();
        }

        RepostLicenseService.CopyContent copyContent = repostLicenseService.createFreshCopyContent(id, userId);
        Map<String, Object> data = buildCopyContentResponse(copyContent);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return Response.ok(body).build();
    }

    private Map<String, Object> buildLicenseResponse(RepostLicense license, RepostLicenseService.CopyContent copyContent) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", license.id);
        data.put("code", license.code);
        data.put("postId", license.article.id);
        data.put("allowedDomain", license.allowedDomain);
        data.put("targetUrl", license.targetUrl);
        data.put("status", license.status);
        data.put("copy", buildCopyContentResponse(copyContent));
        return data;
    }

    private Map<String, Object> buildCopyContentResponse(RepostLicenseService.CopyContent copyContent) {
        Map<String, Object> data = new HashMap<>();
        data.put("markdown", copyContent.markdown);
        data.put("html", copyContent.html);
        data.put("plainText", copyContent.plainText);
        data.put("originalUrl", copyContent.originalUrl);
        data.put("goUrl", copyContent.goUrl);
        return data;
    }

    private Long resolveUserId(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null) {
            return null;
        }
        if (cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }

        UserTokenVerifier.VerifiedToken verifiedToken = tokenVerifier.verify(cookie.getValue());
        if (verifiedToken == null) {
            return null;
        }
        return verifiedToken.uid();
    }

    private Response unauthorized() {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "未登录或登录已过期");
        return Response.status(Response.Status.UNAUTHORIZED).entity(body).build();
    }

    public static class LicenseCreateRequest {
        public Long postId;
        public String targetUrl;
    }
}
