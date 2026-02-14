package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult; // Reuse PageResult
import com.biliwind.blog.model.Comment;
import io.quarkus.panache.common.Page;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminComment")
public class AdminCommentController {

    @GET
    @Operation(summary = "评论列表")
    public PageResult<AdminCommentItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("10") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("keyword") String keyword) {

        StringBuilder where = new StringBuilder("deletedAt is null");
        Map<String, Object> params = new HashMap<>();

        if (status != null) {
            where.append(" and status = :status");
            params.put("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" and content like :keyword");
            params.put("keyword", "%" + keyword.trim() + "%");
        }

        var query = Comment.find(where.toString() + " order by createdAt desc", params);
        List<Comment> list = query.page(Page.of(page - 1, pageSize)).list();

        return new PageResult<>(
                list.stream().map(this::toItem).toList(),
                query.count(),
                page,
                pageSize);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新评论状态")
    public AdminCommentItem update(@PathParam("id") Long id, CommentUpdateRequest req) {
        Comment comment = Comment.findById(id);
        if (comment == null || comment.deletedAt != null)
            throw new NotFoundException();

        if (req.status() != null) {
            comment.status = req.status();
        }
        if (req.content() != null && !req.content().isBlank()) {
            comment.content = req.content();
        }

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
        }
    }

    private AdminCommentItem toItem(Comment c) {
        return new AdminCommentItem(
                c.id,
                c.post.id,
                c.post.title.get("zh-cn"), // Simplified
                c.user != null ? c.user.id : null,
                c.user != null ? c.user.username : "Guest",
                c.content,
                c.parent != null ? c.parent.id : null,
                c.status,
                c.createdAt);
    }
}
