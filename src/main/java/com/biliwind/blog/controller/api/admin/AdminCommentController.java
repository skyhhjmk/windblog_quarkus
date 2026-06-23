package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.CommentQuote;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.ai.AiManager;
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

@Path("/api/admin/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin - Comment", description = "评论管理")
public class AdminCommentController {

    @Inject
    AiManager aiManager;

    @Inject
    com.biliwind.blog.service.ai.AiTaskProducer aiTaskProducer;

    @Inject
    AuditService auditService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    private void invalidateCommentCaches() {
        // 清理侧边栏统计
        cacheService.delete(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_STATS);
    }

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

        String queryStr = "from Comment c left join fetch c.post left join fetch c.user where " + where.toString().replace("deletedAt", "c.deletedAt").replace("status", "c.status") + " order by c.createdAt desc";
        PanacheQuery<Comment> query = Comment.find(queryStr, params);

        Page pageObj = Page.of(safePage - 1, safePageSize);
        List<Comment> comments = query.page(pageObj).list();

        long totalCount = query.count();

        List<AdminCommentItem> items = new ArrayList<AdminCommentItem>();
        for (Comment comment : comments) {
            AdminCommentItem item = toItem(comment);
            items.add(item);
        }

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
            oldVal.put("auditStatus", comment.auditStatus);
            
            comment.status = req.status();

            if (comment.status == 1) {
                comment.auditStatus = 2; // 通过
            } else {
                comment.auditStatus = 3; // 拒绝
            }
            
            newVal.put("status", comment.status);
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
        invalidateCommentCaches();

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
            invalidateCommentCaches();
        }
    }

    @POST
    @Path("/{id}/audit")
    @Operation(summary = "AI 审核评论")
    public AdminCommentItem audit(@PathParam("id") final Long id) {
        // 定义一个临时类来携带数据
        class AuditData {
            AdminCommentItem item;
            String content;
        }

        final AuditData data = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> {
            Comment c = Comment.findById(id);
            if (c == null || c.deletedAt != null) {
                throw new jakarta.ws.rs.NotFoundException();
            }
            if (!c.isReviewing) {
                c.isReviewing = true;
                c.persist();
            }
            AuditData ad = new AuditData();
            ad.item = toItem(c);
            ad.content = c.content;
            return ad;
        });

        // 事务提交后发送异步任务
        aiTaskProducer.sendAuditTask(id, data.content);

        return data.item;
    }

    private AdminCommentItem toItem(Comment comment) {
        String displayTitle = "Deleted Post";
        if (comment.post != null) {
            try {
                String postTitle = resolveLocalizedTitle(comment.post.title);
                if (postTitle != null && !postTitle.isBlank()) {
                    displayTitle = postTitle;
                } else {
                    displayTitle = comment.post.slug;
                }
            } catch (jakarta.persistence.EntityNotFoundException e) {
                // 如果文章被物理删除但评论残留（孤儿数据），捕获异常并显示为已删除
                displayTitle = "Deleted Post (ID: " + comment.post.id + ")";
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
                comment.aiReviewData,
                comment.isReviewing,
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

    @PUT
    @Path("/quotes/{quoteId}/status")
    @Transactional
    @Operation(summary = "处理纠错状态")
    public void updateQuoteStatus(@PathParam("quoteId") Long quoteId, @QueryParam("status") short status) {
        CommentQuote quote = CommentQuote.findById(quoteId);
        if (quote == null) {
            throw new NotFoundException("Quote not found");
        }

        Map<String, Object> oldVal = new HashMap<>();
        oldVal.put("status", quote.status);

        quote.status = status;
        quote.updatedAt = OffsetDateTime.now();

        Map<String, Object> newVal = new HashMap<>();
        newVal.put("status", quote.status);

        auditService.log("comment_quote", String.valueOf(quote.id), "update_status", oldVal, newVal);
        invalidateCommentCaches();
    }
}
