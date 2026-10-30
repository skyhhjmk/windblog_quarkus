package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.AffiliateToken;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.RedirectClickEvent;
import com.biliwind.blog.model.RepostLicense;
import com.biliwind.blog.model.RepostReferralEvent;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.edge.DataSyncEvent;
import com.biliwind.blog.service.repost.RepostDomainService;
import com.biliwind.blog.service.repost.RepostLicenseService;
import com.biliwind.blog.service.repost.RepostPolicyCatalog;
import com.biliwind.blog.service.repost.SemanticWatermarkService;
import com.biliwind.blog.service.PostAccessService;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    PostAccessService postAccessService;

    @Inject
    UserTokenVerifier tokenVerifier;

    @Inject
    RepostDomainService repostDomainService;

    @Inject
    SemanticWatermarkService semanticWatermarkService;

    @Inject
    Event<DataSyncEvent> dataSyncEvent;

    @POST
    @Operation(summary = "申请或自愿登记转载")
    public Response createLicense(LicenseCreateRequest request, @Context HttpHeaders httpHeaders) {
        String language = resolveLanguage(request == null ? null : request.language, httpHeaders);
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized(language);
        }

        User user = User.findById(userId);
        if (user != null && user.repostBanned) {
            String reason = user.repostBanReason == null || user.repostBanReason.isBlank()
                    ? ""
                    : localized(language, "原因：" + user.repostBanReason, "Reason: " + user.repostBanReason);
            String message = localized(language,
                    "当前账号已被限制转载权限，无法申请或登记新的转载授权。",
                    "Reposting is disabled for this account; you cannot request or register new repost licenses.");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "banned", true,
                            "message", reason.isEmpty() ? message : message + " " + reason))
                    .build();
        }

        Long postId = resolvePostId(request);
        if (postId == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message",
                            localized(language, "文章不存在", "Article not found")))
                    .build();
        }
        Post post = Post.findById(postId);
        if (post != null && !postAccessService.hasUnlockedAllPaidContent(post, userId)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", localized(language,
                            "必须解锁全部文章付费区块才能转载",
                            "Unlock all paid sections of this article before reposting")))
                    .build();
        }
        RepostLicenseService.LicenseCreationResult result =
                repostLicenseService.createLicense(postId, userId, request.targetUrl, language);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", buildLicenseResponse(result.license, result.copyContent, language));
        return Response.ok(body).build();
    }

    @GET
    @Transactional
    @Operation(summary = "当前用户的转载授权列表")
    public Response listLicenses(@QueryParam("page") @DefaultValue("1") int page,
                                 @QueryParam("pageSize") @DefaultValue("20") int pageSize,
                                 @QueryParam("lang") String requestedLanguage,
                                 @Context HttpHeaders httpHeaders) {
        String language = resolveLanguage(requestedLanguage, httpHeaders);
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized(language);
        }
        User user = User.findById(userId);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<RepostLicense> licenses = RepostLicense.find(
                        "viewerUser.id = ?1 order by createdAt desc", userId)
                .page(Math.max(page, 1) - 1, safePageSize).list();
        List<Map<String, Object>> items = new ArrayList<>();
        for (RepostLicense license : licenses) {
            items.add(buildLicenseListItem(license, language));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("items", items);
        data.put("total", RepostLicense.count("viewerUser.id = ?1", userId));
        data.put("page", Math.max(page, 1));
        data.put("pageSize", safePageSize);
        data.put("repostBanned", user != null && user.repostBanned);
        data.put("repostBanReason", user == null || user.repostBanReason == null ? "" : user.repostBanReason);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return Response.ok(body).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "修改自己的转载页面地址")
    public Response updateLicense(@PathParam("id") Long id,
                                  LicenseUpdateRequest request,
                                  @Context HttpHeaders httpHeaders) {
        String language = resolveLanguage(request == null ? null : request.language, httpHeaders);
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized(language);
        }
        RepostLicense license = findOwnedLicense(id, userId);
        if (license == null) {
            return notFound(language);
        }
        if (license.status != 1) {
            return badRequest(language,
                    "转载授权已失效，无法修改",
                    "This repost license is no longer active and cannot be edited.");
        }
        if (request == null || request.targetUrl == null || request.targetUrl.isBlank()) {
            return badRequest(language, "转载页面 URL 不能为空", "The reposted page URL is required.");
        }
        String allowedDomain = repostDomainService.normalizeDomainFromUrl(request.targetUrl);
        if (allowedDomain == null || allowedDomain.isBlank()) {
            return badRequest(language, "目标站点 URL 无法识别域名",
                    "The reposted page URL does not contain a valid domain.");
        }
        license.targetUrl = request.targetUrl.trim();
        license.allowedDomain = allowedDomain;
        license.semanticWatermarkConfig = semanticWatermarkService.buildWatermarkConfig(
                userId, license.article.id, allowedDomain);
        List<AffiliateToken> tokens = AffiliateToken.list(
                "repostLicense.id = ?1", license.id);
        for (AffiliateToken token : tokens) {
            token.allowedDomain = allowedDomain;
            dataSyncEvent.fire(new DataSyncEvent("AFFILIATE_TOKEN", token.id, "UPSERT"));
        }
        dataSyncEvent.fire(new DataSyncEvent("REPOST_LICENSE", license.id, "UPSERT"));

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", buildLicenseListItem(license, language));
        return Response.ok(body).build();
    }

    @POST
    @Path("/{id}/revoke")
    @Transactional
    @Operation(summary = "撤销自己的转载授权")
    public Response revokeLicense(@PathParam("id") Long id,
                                  @QueryParam("lang") String requestedLanguage,
                                  @Context HttpHeaders httpHeaders) {
        String language = resolveLanguage(requestedLanguage, httpHeaders);
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized(language);
        }
        RepostLicense license = findOwnedLicense(id, userId);
        if (license == null) {
            return notFound(language);
        }
        if (license.status == 1) {
            license.status = 2;
            license.revokedAt = OffsetDateTime.now();
            dataSyncEvent.fire(new DataSyncEvent("REPOST_LICENSE", license.id, "UPSERT"));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", buildLicenseListItem(license, language));
        return Response.ok(body).build();
    }

    @GET
    @Path("/{id}/copy")
    @Operation(summary = "获取转载复制内容")
    public Response getCopyContent(@PathParam("id") Long id,
                                   @QueryParam("lang") String requestedLanguage,
                                   @Context HttpHeaders httpHeaders) {
        String language = resolveLanguage(requestedLanguage, httpHeaders);
        Long userId = resolveUserId(httpHeaders);
        if (userId == null) {
            return unauthorized(language);
        }
        RepostLicenseService.CopyContent copyContent =
                repostLicenseService.createFreshCopyContent(id, userId, language);
        Map<String, Object> data = buildCopyContentResponse(copyContent);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return Response.ok(body).build();
    }

    private Map<String, Object> buildLicenseResponse(RepostLicense license,
                                                     RepostLicenseService.CopyContent copyContent,
                                                     String language) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", license.id);
        data.put("code", license.code);
        data.put("postSlug", license.article.slug);
        data.put("allowedDomain", license.allowedDomain);
        data.put("targetUrl", license.targetUrl);
        data.put("status", license.status);
        RepostPolicyCatalog.Policy policy = repostPolicyCatalog.resolve(license.article);
        data.put("repostPolicyCode", policy.code());
        data.put("repostPolicyName", policy.nameFor(language));
        data.put("repostPolicySummary", policy.summaryFor(language));
        data.put("repostPolicyConditions", policy.conditionsFor(language));
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

    private Response unauthorized(String language) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", localized(language, "未登录或登录已过期", "Not signed in or the session has expired"));
        return Response.status(Response.Status.UNAUTHORIZED).entity(body).build();
    }

    private String localized(String language, String chinese, String english) {
        return RepostPolicyCatalog.isEnglish(language) ? english : chinese;
    }

    private RepostLicense findOwnedLicense(Long id, Long userId) {
        if (id == null) {
            return null;
        }
        RepostLicense license = RepostLicense.findById(id);
        if (license == null || license.viewerUser == null || license.viewerUser.id == null) {
            return null;
        }
        return license.viewerUser.id.equals(userId) ? license : null;
    }

    private Map<String, Object> buildLicenseListItem(RepostLicense license, String language) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", license.id);
        item.put("code", license.code);
        item.put("postSlug", license.article == null ? null : license.article.slug);
        item.put("postTitle", license.article == null ? null
                : LanguageHelper.resolveLocalizedValue(license.article.title, language));
        item.put("targetUrl", license.targetUrl);
        item.put("allowedDomain", license.allowedDomain);
        item.put("status", license.status);
        item.put("createdAt", license.createdAt);
        item.put("revokedAt", license.revokedAt);
        item.put("originalClicks", RepostReferralEvent.count("repostLicense.id = ?1", license.id));
        item.put("goClicks", RedirectClickEvent.count("repostLicense.id = ?1", license.id));
        RepostPolicyCatalog.Policy policy = repostPolicyCatalog.resolve(license.article);
        item.put("repostPolicyCode", policy.code());
        item.put("repostPolicyName", policy.nameFor(language));
        item.put("requiresApplication", policy.requiresApplication());
        return item;
    }

    private Response notFound(String language) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("success", false, "message",
                        localized(language, "转载授权不存在", "Repost license not found")))
                .build();
    }

    private Response badRequest(String language, String chinese, String english) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("success", false, "message", localized(language, chinese, english)))
                .build();
    }

    private String resolveLanguage(String requestedLanguage, HttpHeaders httpHeaders) {
        String normalized = LanguageHelper.normalizeToSupportedLang(requestedLanguage);
        if (normalized != null) {
            return normalized;
        }
        String fromHeader = LanguageHelper.resolveFromAcceptLanguage(
                httpHeaders == null ? null : httpHeaders.getHeaderString("Accept-Language"));
        return fromHeader == null ? LanguageConstant.DEFAULT_LANG : fromHeader;
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

    public static class LicenseUpdateRequest {
        public String targetUrl;
        public String language;
    }

    public static class LicenseCreateRequest {
        /** Legacy field for existing clients; public pages now send postSlug. */
        public Long postId;
        public String postSlug;
        public String targetUrl;
        /** UI language of the public page that generated the copy. */
        public String language;
    }
}
