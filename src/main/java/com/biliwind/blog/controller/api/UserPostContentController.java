package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.context.RegionContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.service.PostAccessService;
import com.biliwind.blog.service.PostAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 付费内容安全获取 API
 * 提供受保护的端点，确保付费内容不会被缓存，且仅授权用户可访问
 */
@Path("/api/user/post")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserPostContent")
public class UserPostContentController {

    @Inject
    PostAccessService postAccessService;

    @Inject
    PostAccessPolicy postAccessPolicy;

    @Inject
    com.biliwind.blog.service.ContentAccessTicketService contentAccessTicketService;

    @Inject
    com.biliwind.blog.service.PublicContentSanitizer publicContentSanitizer;

    @Inject
    LanguageContext languageContext;

    @Inject
    RegionContext regionContext;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "cookie.secure", defaultValue = "false")
    boolean cookieSecure;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    /**
     * 安全获取文章完整内容（含付费内容）
     * <p>
     * 关键安全特性：
     * 1. 严格验证用户购买权限
     * 2. 密码保护文章必须提供正确密码
     * 3. 设置禁止缓存的响应头
     * 4. 仅返回已授权的完整内容
     */
    @GET
    @Path("/content/{postId}")
    @Operation(summary = "获取文章完整付费内容（安全端点）")
    public Response getPostContent(@PathParam("postId") String postRef, @Context HttpHeaders headers) {
        Long postId = resolvePostId(postRef);
        if (postId == null) {
            return postNotFound();
        }
        Long userId = resolveUserId(headers);

        Post post = Post.find(
                "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                postId,
                com.biliwind.blog.model.PostStatus.PUBLISHED
        ).firstResult();
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在"))
                    .build();
        }
        if (!postAccessPolicy.isVisibleInRegion(post, regionContext.getCurrentRegion())) {
            return postNotFound();
        }

        Cookie passwordTicketCookie = headers.getCookies().get("post_access_ticket_" + postId);
        String passwordTicket = passwordTicketCookie == null ? null : passwordTicketCookie.getValue();
        PostAccessPolicy.Decision access = postAccessPolicy.evaluate(
                post, userId, null, passwordTicket, headers.getHeaderString("X-Device-Id"));
        if (!access.allowed()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "没有权限访问此文章"))
                    .build();
        }

        // 密码保护校验：密码保护文章必须提供正确密码才能获取内容
        if (post.visibility == 2) {
            boolean passwordPassed = checkPasswordAccess(post, postId, headers);
            if (!passwordPassed) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("success", false, "message", "需要密码才能访问此文章"))
                        .build();
            }
        }

        long postPrice = postAccessService.getPostPrice(post);
        boolean isAuthor = userId != null && post.user != null && userId.equals(post.user.id);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(userId, postId);

        // 判定逻辑：如果是作者，或者已经支付过（哪怕是0积分），允许进入安全端点
        // 安全端点内部会根据 maxPointsPaid 进一步过滤每个区块
        if (!isAuthor && maxPointsPaid < 0 && postPrice > 0) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "请先解锁本文"))
                    .build();
        }

        String resolvedLang = languageContext.getLang();
        String localizedContent = resolveContent(post.publishedRevision, resolvedLang);
        localizedContent = postAccessService.filterHiddenContent(localizedContent, maxPointsPaid, isAuthor, postId, postPrice, userId);

        // 全站买断判定：只依赖明确的全文购买记录，不用 maxPointsPaid 比较
        boolean hasPurchased = isAuthor
                || postAccessService.hasPurchasedPost(userId, postId);

        localizedContent = postAccessService.rewriteProtectedMediaReferences(
                postId, localizedContent, userId, hasPurchased, headers.getHeaderString("X-Device-Id"));

        PostBodyView postBody = resolvePostBody(post.renderType, localizedContent);

        List<AttachmentView> attachments = hasPurchased ? PostMedia.<PostMedia>list("post.id = ?1", postId).stream()
                .filter(pm -> pm.usageType == 3)
                .map(pm -> {
                    long bytes = pm.media.size != null ? pm.media.size : 0;
                    String formattedSize = bytes < 1024 * 1024
                            ? (bytes / 1024) + " KB"
                            : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                    String downloadUrl = "/api/media/download/"
                            + contentAccessTicketService.issueMediaDownloadPath(
                            pm.media.id,
                            postId,
                            userId,
                            java.time.Duration.ofMinutes(10),
                            headers.getHeaderString("X-Device-Id"));
                    return new AttachmentView(pm.media.fileName, downloadUrl, formattedSize);
                })
                .collect(Collectors.toList()) : List.of();

        return Response.ok(Map.of(
                        "success", true,
                        "data", Map.of(
                                "content", postBody.body(),
                                "contentHtml", postBody.html(),
                                "renderType", postBody.renderType() != null ? postBody.renderType().name() : null,
                                "attachments", attachments,
                                "hasPurchased", hasPurchased
                        )
                ))
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build();
    }

    /**
     * 安全获取当前用户已解锁的文章区块内容
     */
    @GET
    @Path("/blocks/{postId}")
    @Operation(summary = "获取文章已解锁区块（安全端点）")
    public Response getUnlockedBlocks(@PathParam("postId") String postRef, @Context HttpHeaders headers) {
        Long postId = resolvePostId(postRef);
        if (postId == null) {
            return postNotFound();
        }
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return Response.ok(Map.of("success", true, "data", Map.of("blocks", Map.of(), "hasPurchased", false))).build();
        }

        Post post = Post.find(
                "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                postId,
                com.biliwind.blog.model.PostStatus.PUBLISHED
        ).firstResult();
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在"))
                    .build();
        }
        if (!postAccessPolicy.isVisibleInRegion(post, regionContext.getCurrentRegion())) {
            return postNotFound();
        }

        if (!postAccessPolicy.evaluate(post, userId,
                null, resolvePasswordTicket(headers, postId),
                headers.getHeaderString("X-Device-Id")).allowed()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "没有权限访问此文章"))
                    .build();
        }

        // 密码保护校验：密码保护文章必须提供正确密码才能获取区块内容
        if (post.visibility == 2) {
            boolean passwordPassed = checkPasswordAccess(post, postId, headers);
            if (!passwordPassed) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("success", false, "message", "需要密码才能访问此文章"))
                        .build();
            }
        }

        long postPrice = postAccessService.getPostPrice(post);
        boolean isAuthor = post.user != null && userId.equals(post.user.id);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(userId, postId);

        String resolvedLang = languageContext.getLang();
        String localizedContent = resolveContent(post.publishedRevision, resolvedLang);

        java.util.Map<String, String> unlockedBlocks = postAccessService.getUnlockedBlocks(localizedContent, maxPointsPaid, isAuthor, postId, postPrice, userId);

        boolean hasPurchased = isAuthor
                || postAccessService.hasPurchasedPost(userId, postId);
        java.util.Map<String, String> rewrittenBlocks = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> entry : unlockedBlocks.entrySet()) {
            rewrittenBlocks.put(entry.getKey(), postAccessService.rewriteProtectedMediaReferences(
                    postId, entry.getValue(), userId, hasPurchased, headers.getHeaderString("X-Device-Id")));
        }

        // 渲染 Markdown
        java.util.Map<String, String> renderedBlocks = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> entry : rewrittenBlocks.entrySet()) {
            PostBodyView postBody = resolvePostBody(post.renderType, entry.getValue());
            renderedBlocks.put(entry.getKey(), postBody.body());
        }

        // 全站买断判定：只依赖明确的全文购买记录，不用 maxPointsPaid 比较
        List<AttachmentView> attachments = hasPurchased ? PostMedia.<PostMedia>list("post.id = ?1", postId).stream()
                .filter(pm -> pm.usageType == 3)
                .map(pm -> {
                    long bytes = pm.media.size != null ? pm.media.size : 0;
                    String formattedSize = bytes < 1024 * 1024
                            ? (bytes / 1024) + " KB"
                            : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                    String downloadUrl = "/api/media/download/"
                            + contentAccessTicketService.issueMediaDownloadPath(
                            pm.media.id,
                            postId,
                            userId,
                            java.time.Duration.ofMinutes(10),
                            headers.getHeaderString("X-Device-Id"));
                    return new AttachmentView(pm.media.fileName, downloadUrl, formattedSize);
                })
                .collect(Collectors.toList()) : List.of();

        return Response.ok(Map.of(
                        "success", true,
                        "data", Map.of(
                                "blocks", renderedBlocks,
                                "hasPurchased", hasPurchased,
                                "attachments", attachments
                        )
                ))
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .build();
    }

    /**
     * 购买文章
     */
    @POST
    @Path("/buy/{postId}")
    @Operation(summary = "购买文章/解锁区块")
    public Response buyPost(@PathParam("postId") String postRef,
                            @QueryParam("price") Long price,
                            @QueryParam("blockId") String blockId,
                            @Context HttpHeaders headers) {
        Long postId = resolvePostId(postRef);
        if (postId == null) {
            return postNotFound();
        }
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
        }

        Post post = Post.find(
                "id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                postId,
                com.biliwind.blog.model.PostStatus.PUBLISHED
        ).firstResult();
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在"))
                    .build();
        }
        if (!postAccessPolicy.isVisibleInRegion(post, regionContext.getCurrentRegion())) {
            return postNotFound();
        }

        if (!postAccessPolicy.evaluate(post, userId,
                null, resolvePasswordTicket(headers, postId),
                headers.getHeaderString("X-Device-Id")).allowed()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "没有权限访问此文章"))
                    .build();
        }

        // 密码保护校验：购买密码保护文章的内容也需要提供密码
        if (post.visibility == 2) {
            boolean passwordPassed = checkPasswordAccess(post, postId, headers);
            if (!passwordPassed) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(Map.of("success", false, "message", "需要密码才能解锁此文章"))
                        .build();
            }
        }

        try {
            postAccessService.buyPost(userId, postId, price, blockId);
            return Response.ok(Map.of("success", true, "message", "解锁成功"))
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .build();
        } catch (com.biliwind.blog.common.exception.InsufficientBalanceException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "积分余额不足，请先充值"))
                    .build();
        } catch (com.biliwind.blog.common.exception.ConcurrentModificationException e) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("success", false, "message", "操作太快了，请稍后重试"))
                    .build();
        } catch (BadRequestException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false,
                            "message", SensitiveMessageSanitizer.sanitize(e.getMessage())))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false,
                            "message", "购买失败：" + SensitiveMessageSanitizer.sanitize(e.getMessage())))
                    .build();
        }
    }

    @POST
    @Path("/password/{postId}")
    @Operation(summary = "验证文章密码并签发短期访问票据")
    public Response unlockPassword(@PathParam("postId") String postRef,
                                   PasswordRequest request,
                                   @Context HttpHeaders headers) {
        Long postId = resolvePostId(postRef);
        if (postId == null) {
            return postNotFound();
        }
        Post post = Post.find("id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                        postId, com.biliwind.blog.model.PostStatus.PUBLISHED).firstResult();
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("success", false, "message", "文章不存在")).build();
        }
        if (!postAccessPolicy.isVisibleInRegion(post, regionContext.getCurrentRegion())) {
            return postNotFound();
        }
        String password = request == null ? null : request.password();
        if (post.visibility != 2 || !postAccessPolicy.evaluate(post, null, password, null).allowed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(Map.of("success", false, "message", "密码错误")).build();
        }

        com.biliwind.blog.service.ContentAccessTicketService.IssuedTicket issued =
                contentAccessTicketService.issuePasswordTicket(postId, java.time.Duration.ofMinutes(15),
                        headers.getHeaderString("X-Device-Id"));
        NewCookie cookie = new NewCookie.Builder("post_access_ticket_" + postId)
                .value(issued.token())
                .path("/")
                .maxAge(900)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(NewCookie.SameSite.LAX)
                .build();
        return Response.ok(Map.of("success", true, "expiresAt", issued.expiresAt().toString()))
                .cookie(cookie)
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/password/{postId}/form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response unlockPasswordForm(@PathParam("postId") String postRef,
                                       @FormParam("password") String password) {
        Long postId = resolvePostId(postRef);
        if (postId == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("文章不存在").type(MediaType.TEXT_HTML).build();
        }
        Post post = Post.find("id = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                        postId, com.biliwind.blog.model.PostStatus.PUBLISHED).firstResult();
        if (post == null) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("密码错误").type(MediaType.TEXT_HTML).build();
        }
        if (!postAccessPolicy.isVisibleInRegion(post, regionContext.getCurrentRegion())) {
            return Response.status(Response.Status.NOT_FOUND).entity("文章不存在").type(MediaType.TEXT_HTML).build();
        }
        if (post.visibility != 2 || !postAccessPolicy.evaluate(post, null, password, null).allowed()) {
            return Response.status(Response.Status.UNAUTHORIZED).entity("密码错误").type(MediaType.TEXT_HTML).build();
        }
        com.biliwind.blog.service.ContentAccessTicketService.IssuedTicket issued =
                contentAccessTicketService.issuePasswordTicket(postId, java.time.Duration.ofMinutes(15), null);
        NewCookie cookie = new NewCookie.Builder("post_access_ticket_" + postId)
                .value(issued.token()).path("/").maxAge(900).httpOnly(true)
                .secure(cookieSecure).sameSite(NewCookie.SameSite.LAX).build();
        return Response.seeOther(UriBuilder.fromPath("/post/{slug}").resolveTemplate("slug", post.slug).build())
                .cookie(cookie).build();
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 校验请求方是否通过了密码保护验证。
     * 密码只接受一次性请求头；后续请求使用服务端签发的短期票据。
     */
    private boolean checkPasswordAccess(Post post, Long postId, HttpHeaders headers) {
        Cookie ticketCookie = headers.getCookies().get("post_access_ticket_" + postId);
        String ticketToken = ticketCookie == null ? null : ticketCookie.getValue();
        return postAccessPolicy.evaluate(post, null, null, ticketToken,
                headers.getHeaderString("X-Device-Id")).allowed();
    }

    private Long resolveUserId(HttpHeaders headers) {
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

    private String resolvePasswordTicket(HttpHeaders headers, Long postId) {
        Cookie cookie = headers.getCookies().get("post_access_ticket_" + postId);
        if (cookie == null) {
            return null;
        }
        return cookie.getValue();
    }

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("success", false, "message", "未登录或登录已过期"))
                .build();
    }

    private Response postNotFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("success", false, "message", "文章不存在"))
                .build();
    }

    /**
     * Accept the legacy numeric route while making new public callers use the slug.
     */
    private Long resolvePostId(String postRef) {
        if (postRef == null || postRef.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(postRef);
        } catch (NumberFormatException ignored) {
            Post post = Post.find(
                    "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                    postRef,
                    com.biliwind.blog.model.PostStatus.PUBLISHED
            ).firstResult();
            return post == null ? null : post.id;
        }
    }

    private String resolveContent(PostRevision revision, String lang) {
        if (revision == null) {
            return null;
        }
        return LanguageHelper.resolveLocalizedValue(revision.contentMarkdown, lang);
    }

    private PostBodyView resolvePostBody(PostRenderType renderType, String content) {
        if (content == null || content.isBlank()) {
            return new PostBodyView("", false, null);
        }

        PostRenderType effective = renderType == null ? PostRenderType.MARKDOWN : renderType;

        return switch (effective) {
            case MARKDOWN, FLUTTER_MARKDOWN_PLUS -> new PostBodyView(MarkdownHelper.toHtml(content), true, effective);
            case VDITOR -> new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK ->
                    new PostBodyView(publicContentSanitizer.sanitize(content), true, effective);
        };
    }

    public record PostBodyView(String body, boolean html, PostRenderType renderType) {
    }

    public record AttachmentView(String fileName, String url, String formattedSize) {
    }

    public record PasswordRequest(String password) {
    }
}
