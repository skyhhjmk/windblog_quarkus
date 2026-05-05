package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.helper.LanguageHelper;
import com.biliwind.blog.common.helper.MarkdownHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.service.PostAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
    LanguageContext languageContext;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    /**
     * 安全获取文章完整内容（含付费内容）
     * <p>
     * 关键安全特性：
     * 1. 严格验证用户购买权限
     * 2. 设置禁止缓存的响应头
     * 3. 仅返回已授权的完整内容
     */
    @GET
    @Path("/content/{postId}")
    @Operation(summary = "获取文章完整付费内容（安全端点）")
    public Response getPostContent(@PathParam("postId") Long postId, @Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);

        Post post = Post.findById(postId);
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在"))
                    .build();
        }

        long postPrice = postAccessService.getPostPrice(post);
        boolean isAuthor = userId != null && post.user != null && userId.equals(post.user.id);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(userId, postId);

        // 判定逻辑：如果是作者，或者已经支付过（哪怕是0积分），允许进入安全端点
        // 安全端点内部会根据 maxPointsPaid 进一步过滤每个区块
        if (!isAuthor && maxPointsPaid < 0) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "请先解锁本文"))
                    .build();
        }

        String resolvedLang = languageContext.getLang();
        String localizedContent = resolveContent(post.currentRevision, resolvedLang);
        localizedContent = postAccessService.filterHiddenContent(localizedContent, maxPointsPaid, isAuthor, postId, postPrice, userId);

        // 全站买断判定
        boolean hasPurchased = isAuthor
                || (postPrice > 0 && maxPointsPaid >= postPrice)
                || postAccessService.hasPurchasedPost(userId, postId);

        PostBodyView postBody = resolvePostBody(post.renderType, localizedContent);

        List<AttachmentView> attachments = hasPurchased ? PostMedia.<PostMedia>list("post.id = ?1", postId).stream()
                .filter(pm -> pm.usageType == 3)
                .map(pm -> {
                    long bytes = pm.media.size != null ? pm.media.size : 0;
                    String formattedSize = bytes < 1024 * 1024
                            ? (bytes / 1024) + " KB"
                            : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                    return new AttachmentView(pm.media.fileName, pm.media.url, formattedSize);
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
    public Response getUnlockedBlocks(@PathParam("postId") Long postId, @Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return Response.ok(Map.of("success", true, "data", Map.of("blocks", Map.of(), "hasPurchased", false))).build();
        }

        Post post = Post.findById(postId);
        if (post == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        long postPrice = postAccessService.getPostPrice(post);
        boolean isAuthor = post.user != null && userId.equals(post.user.id);
        long maxPointsPaid = postAccessService.getMaxPointsPaid(userId, postId);

        String resolvedLang = languageContext.getLang();
        String localizedContent = resolveContent(post.currentRevision, resolvedLang);

        java.util.Map<String, String> unlockedBlocks = postAccessService.getUnlockedBlocks(localizedContent, maxPointsPaid, isAuthor, postId, postPrice, userId);

        // 渲染 Markdown
        java.util.Map<String, String> renderedBlocks = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> entry : unlockedBlocks.entrySet()) {
            PostBodyView postBody = resolvePostBody(post.renderType, entry.getValue());
            renderedBlocks.put(entry.getKey(), postBody.body());
        }

        boolean hasPurchased = isAuthor
                || (postPrice > 0 && maxPointsPaid >= postPrice)
                || postAccessService.hasPurchasedPost(userId, postId);

        List<AttachmentView> attachments = hasPurchased ? PostMedia.<PostMedia>list("post.id = ?1", postId).stream()
                .filter(pm -> pm.usageType == 3)
                .map(pm -> {
                    long bytes = pm.media.size != null ? pm.media.size : 0;
                    String formattedSize = bytes < 1024 * 1024
                            ? (bytes / 1024) + " KB"
                            : String.format("%.2f MB", bytes / (1024.0 * 1024.0));
                    return new AttachmentView(pm.media.fileName, pm.media.url, formattedSize);
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
    public Response buyPost(@PathParam("postId") Long postId,
                            @QueryParam("price") Long price,
                            @QueryParam("blockId") String blockId,
                            @Context HttpHeaders headers) {
        Long userId = resolveUserId(headers);
        if (userId == null) {
            return unauthorized();
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
                    .entity(Map.of("success", false, "message", e.getMessage()))
                    .build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("success", false, "message", "购买失败：" + e.getMessage()))
                    .build();
        }
    }

    // ==================== 私有辅助方法 ====================

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

    private Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("success", false, "message", "未登录或登录已过期"))
                .build();
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
            case VDITOR -> new PostBodyView(content, false, effective);
            case HTML, V_BUILDER, GUTENBERG, FLUTTER_QUILL, TUTORIAL_BLOCK ->
                    new PostBodyView(content, true, effective);
        };
    }

    public record PostBodyView(String body, boolean html, PostRenderType renderType) {
    }

    public record AttachmentView(String fileName, String url, String formattedSize) {
    }
}
