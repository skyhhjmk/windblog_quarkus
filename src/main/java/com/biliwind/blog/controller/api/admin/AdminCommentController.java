package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Comment;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminComment")
@SecurityRequirement(name = "adminBearerAuth")
public class AdminCommentController {

    @Inject
    com.biliwind.blog.service.ai.AiManager aiManager;

    @Inject
    com.biliwind.blog.service.AuditService auditService;

    @Inject
    com.biliwind.blog.context.AdminRequestContext adminRequestContext;

    @GET
    @Transactional
    @Operation(summary = "评论列表")
    public PageResult<AdminCommentItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("keyword") String keyword) {

        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        StringBuilder where = new StringBuilder("deletedAt is null");
        Map<String, Object> params = new HashMap<>();

        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(content) like :keyword or lower(user.username) like :keyword or lower(post.slug) like :keyword)");
            params.put("keyword", "%" + keyword.trim().toLowerCase() + "%");
        }

        var query = Comment.find(where + " order by createdAt desc", params);
        List<Comment> comments = query.page(Page.of(safePage - 1, safePageSize)).list();

        return new PageResult<>(
                comments.stream().map(this::toItem).toList(),
                query.count(),
                safePage,
                safePageSize);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新评论状态")
    public AdminCommentItem update(@PathParam("id") Long id, CommentUpdateRequest req) {
        Comment comment = Comment.findById(id);
        if (comment == null || comment.deletedAt != null) {
            throw new NotFoundException();
        }

        Map<String, Object> oldVal = new HashMap<>();
        Map<String, Object> newVal = new HashMap<>();

        if (req.status() != null) {
            oldVal.put("status", comment.status);
            comment.status = req.status();
            newVal.put("status", comment.status);
        }
        if (req.content() != null && !req.content().isBlank()) {
            oldVal.put("content", comment.content);
            comment.content = req.content().trim();
            newVal.put("content", comment.content);
        }

        auditService.log("comment", comment.id, "update", oldVal, newVal);

        return toItem(comment);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "删除评论")
    public void delete(@PathParam("id") Long id) {
        Comment comment = Comment.findById(id);
        if (comment != null) {
            comment.deletedAt = OffsetDateTime.now();
            auditService.log("comment", comment.id, "delete", Map.of("deleted", false), Map.of("deleted", true));
        }
    }

    @POST
    @Path("/{id}/audit")
    @Transactional
    @Operation(summary = "AI 审核评论")
    public java.util.concurrent.CompletionStage<AdminCommentItem> audit(@PathParam("id") Long id) {
        Comment comment = Comment.findById(id);
        if (comment == null || comment.deletedAt != null) {
            throw new NotFoundException();
        }

        long startTime = System.currentTimeMillis();
        Long commentId = comment.id;
        // 在异步调用前捕获用户ID，因为 RequestContext 无法传递到异步线程
        Long performingUserId = adminRequestContext.getUserId();

        return aiManager.moderate(comment.content).thenApply(new java.util.function.Function<com.biliwind.blog.service.ai.AiResult, AdminCommentItem>() {
            @Override
            public AdminCommentItem apply(com.biliwind.blog.service.ai.AiResult aiResult) {
                long endTime = System.currentTimeMillis();
                long durationMs = endTime - startTime;

                io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
                    @Override
                    public void run() {
                        Comment c = Comment.findById(commentId);
                        if (c == null) {
                            return;
                        }

                        short oldStatus = c.status;
                        c.status = aiResult.isSafe ? (short) 1 : (short) 2;

                        // 写入审计日志
                        auditService.log("comment", c.id, "ai_moderation",
                                Map.of("status", oldStatus),
                                Map.of("status", c.status, "isSafe", aiResult.isSafe),
                                durationMs, aiResult.inputTokens, aiResult.outputTokens, aiResult.totalTokens);
                    }
                });

                Comment finalComment = Comment.findById(commentId);
                return toItem(finalComment);
            }
        });
    }

    private AdminCommentItem toItem(Comment comment) {
        String postTitle = comment.post == null ? null : resolveLocalizedTitle(comment.post.title);
        return new AdminCommentItem(
                comment.id,
                comment.post.id,
                postTitle != null && !postTitle.isBlank() ? postTitle : comment.post.slug,
                comment.user != null ? comment.user.id : null,
                comment.user != null ? comment.user.username : "Guest",
                comment.content,
                comment.parent != null ? comment.parent.id : null,
                comment.status,
                comment.createdAt);
    }

    private String resolveLocalizedTitle(Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) {
            return null;
        }
        if (titles.containsKey("zh-CN")) {
            return titles.get("zh-CN");
        }
        if (titles.containsKey("zh-cn")) {
            return titles.get("zh-cn");
        }
        return titles.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse(null);
    }
}
