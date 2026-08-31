package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.service.repost.RepostLicenseService;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
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
    RepostPolicyCatalog repostPolicyCatalog;

    @Inject
    UserTokenVerifier tokenVerifier;

    @POST
    @Operation(summary = "申请或自愿登记转载")
    public Response createLicense(LicenseCreateRequest request, @Context HttpHeaders httpHeaders) {
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized();
        }

        Long postId = resolvePostId(request);
        if (postId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在"))
                    .build();
        }
        RepostLicenseService.LicenseCreationResult result =
                repostLicenseService.createLicense(postId, userId, request.targetUrl);

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
        data.put("postSlug", license.article.slug);
        data.put("allowedDomain", license.allowedDomain);
        data.put("targetUrl", license.targetUrl);
        data.put("status", license.status);
        RepostPolicyCatalog.Policy policy = repostPolicyCatalog.resolve(license.article);
        data.put("repostPolicyCode", policy.code());
        data.put("repostPolicyName", policy.name());
        data.put("requiresApplication", policy.requiresApplication());
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

    private Long resolvePostId(LicenseCreateRequest request) {
        if (request == null) {
            return null;
        }
        if (request.postSlug != null && !request.postSlug.isBlank()) {
            Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                    request.postSlug, com.biliwind.blog.model.PostStatus.PUBLISHED).firstResult();
            return post == null ? null : post.id;
        }
        return request.postId;
    }

    public static class LicenseCreateRequest {
        /** Legacy field for existing clients; public pages now send postSlug. */
        public Long postId;
        public String postSlug;
        public String targetUrl;
    }
}
