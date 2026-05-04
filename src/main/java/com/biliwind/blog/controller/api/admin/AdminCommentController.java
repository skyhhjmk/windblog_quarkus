package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiResult;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@Path("/api/admin/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin - Comment", description = "评论管理")
public class AdminCommentController {

    @Inject
    AiManager aiManager;

    @Inject
    AuditService auditService;

    @Inject
    AdminRequestContext adminRequestContext;

    @GET
    @Transactional
    @Operation(summary = "评论列表")
    public PageResult<AdminCommentItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("status") Short status) {

        int safePage = 1;
        if (page > 1) {
            safePage = page;
        }

        int safePageSize = 20;
        if (pageSize >= 1) {
            if (pageSize <= 100) {
                safePageSize = pageSize;
            } else {
                safePageSize = 100;
            }
        } else {
            safePageSize = 1;
        }

        StringBuilder where = new StringBuilder();
        where.append("deletedAt is null");

        Map<String, Object> params = new HashMap<String, Object>();

        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }

        String queryStr = where.toString() + " order by createdAt desc";
        PanacheQuery<Comment> query = Comment.find(queryStr, params);

        Page pageObj = Page.of(safePage - 1, safePageSize);
        List<Comment> comments = query.page(pageObj).list();

        List<AdminCommentItem> items = new ArrayList<AdminCommentItem>();
        for (Comment comment : comments) {
            AdminCommentItem item = toItem(comment);
            items.add(item);
        }

        long totalCount = query.count();

        return new PageResult<AdminCommentItem>(
                items,
                totalCount,
                safePage,
                safePageSize
        );
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新评论")
    public AdminCommentItem update(@PathParam("id") Long id, CommentUpdateRequest req) {
        Comment comment = Comment.findById(id);
        if (comment == null) {
            throw new NotFoundException();
        }
        if (comment.deletedAt != null) {
            throw new NotFoundException();
        }

        Map<String, Object> oldVal = new HashMap<String, Object>();
        Map<String, Object> newVal = new HashMap<String, Object>();

        if (req.status() != null) {
            oldVal.put("status", comment.status);
            oldVal.put("auditType", comment.auditType);
            oldVal.put("auditStatus", comment.auditStatus);
            
            comment.status = req.status();
            comment.auditType = 2; // 人工审核

            if (comment.status == 1) {
                comment.auditStatus = 2; // 通过
            } else {
                comment.auditStatus = 3; // 拒绝
            }
            
            newVal.put("status", comment.status);
            newVal.put("auditType", comment.auditType);
            newVal.put("auditStatus", comment.auditStatus);
        }

        if (req.content() != null) {
            if (!req.content().isBlank()) {
                oldVal.put("content", comment.content);
                comment.content = req.content().trim();
                newVal.put("content", comment.content);
            }
        }

        auditService.log("comment", String.valueOf(comment.id), "update", oldVal, newVal);

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
            auditService.log("comment", String.valueOf(id), "delete", null, null);
        }
    }

    @POST
    @Path("/{id}/audit")
    @Transactional
    @Operation(summary = "AI 审核评论")
    public CompletionStage<AdminCommentItem> audit(@PathParam("id") final Long id) {
        final Comment comment = Comment.findById(id);
        if (comment == null) {
            throw new NotFoundException();
        }
        if (comment.deletedAt != null) {
            throw new NotFoundException();
        }

        final long startTime = System.currentTimeMillis();
        final Long commentId = comment.id;
        final Long performingUserId = adminRequestContext.getUserId();

        return aiManager.moderate(comment.content).thenApply(new Function<AiResult, AdminCommentItem>() {
            @Override
            public AdminCommentItem apply(final AiResult aiResult) {
                final long durationMs = System.currentTimeMillis() - startTime;

                return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(new Callable<AdminCommentItem>() {
                    @Override
                    public AdminCommentItem call() {
                        Comment c = Comment.findById(commentId);
                        if (c == null) {
                            throw new NotFoundException("Comment not found");
                        }

                        short oldStatus = c.status;

                        boolean safe = false;
                        if (aiResult.isSafe != null) {
                            if (aiResult.isSafe == true) {
                                safe = true;
                            }
                        }

                        if (safe) {
                            c.status = 1;
                            c.auditStatus = 2;
                            c.auditReason = "AI 判定内容安全";
                        } else {
                            c.status = 2;
                            c.auditStatus = 3;
                            if (aiResult.errorMessage != null) {
                                c.auditReason = aiResult.errorMessage;
                            } else {
                                c.auditReason = "AI 判定内容存在风险";
                            }
                        }
                        
                        c.auditType = 1; // AI 审核
                        c.aiDurationMs = durationMs;
                        c.aiTotalTokens = aiResult.totalTokens;
                        c.aiScore = aiResult.score;

                        // 写入审计日志
                        Map<String, Object> extInfo = new HashMap<String, Object>();
                        extInfo.put("durationMs", durationMs);
                        extInfo.put("inputTokens", aiResult.inputTokens);
                        extInfo.put("outputTokens", aiResult.outputTokens);
                        extInfo.put("totalTokens", aiResult.totalTokens);
                        extInfo.put("score", aiResult.score);
                        extInfo.put("rawResponse", aiResult.rawResponse);

                        Map<String, Object> oldStatusMap = new HashMap<String, Object>();
                        oldStatusMap.put("status", oldStatus);

                        Map<String, Object> newStatusMap = new HashMap<String, Object>();
                        newStatusMap.put("status", (int) c.status);
                        newStatusMap.put("isSafe", aiResult.isSafe);

                        auditService.log("comment", String.valueOf(c.id), "ai_moderation",
                                oldStatusMap,
                                newStatusMap,
                                extInfo,
                                performingUserId);

                        return toItem(c);
                    }
                });
            }
        });
    }

    private AdminCommentItem toItem(Comment comment) {
        String postTitle = null;
        if (comment.post != null) {
            postTitle = resolveLocalizedTitle(comment.post.title);
        }

        String displayTitle = "Deleted Post";
        if (comment.post != null) {
            if (postTitle != null) {
                if (!postTitle.isBlank()) {
                    displayTitle = postTitle;
                } else {
                    displayTitle = comment.post.slug;
                }
            } else {
                displayTitle = comment.post.slug;
            }
        }

        String userName = "Guest";
        if (comment.user != null) {
            userName = comment.user.username;
        }

        Long parentId = null;
        if (comment.parent != null) {
            parentId = comment.parent.id;
        }

        return new AdminCommentItem(
                comment.id,
                comment.post != null ? comment.post.id : null,
                displayTitle,
                comment.user != null ? comment.user.id : null,
                userName,
                comment.content,
                parentId,
                comment.status,
                comment.auditStatus,
                comment.auditType,
                comment.auditReason,
                comment.aiDurationMs,
                comment.aiTotalTokens,
                comment.aiScore,
                comment.createdAt);
    }

    private String resolveLocalizedTitle(Map<String, String> titles) {
        if (titles == null) {
            return null;
        }
        if (titles.isEmpty()) {
            return null;
        }
        
        if (titles.containsKey("zh-CN")) {
            return titles.get("zh-CN");
        }
        if (titles.containsKey("zh-cn")) {
            return titles.get("zh-cn");
        }

        for (String value : titles.values()) {
            if (value != null) {
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}
